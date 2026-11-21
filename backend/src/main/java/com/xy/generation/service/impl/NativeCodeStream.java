package com.xy.generation.service.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.AiServiceStreamingEvent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 对外保留 XY 的 SSE 事件；模型先送分片，最后单独送结束原因和完整候选稿。 */
final class NativeCodeStream {
    interface Writer extends ChatMemoryAccess {
        Flux<AiServiceStreamingEvent> write(@MemoryId Object turn, @dev.langchain4j.service.UserMessage String request);
    }

    // 实例标识每次调用。晚到的旧回调只能写旧草稿；已提交记忆由 GenerationMemory 管理。
    private static final class Turn {
        final List<ChatMessage> previous;
        Turn(List<ChatMessage> previous) { this.previous = List.copyOf(previous); }
    }

    record Chunk(String delta, ChatResponse finalResponse) {}
    private final StreamingChatModel model;
    private final Writer plainWriter;
    private final boolean guarded;

    NativeCodeStream(StreamingChatModel model, boolean guarded) {
        this.model = model;
        this.guarded = guarded;
        this.plainWriter = guarded ? null : writer(model, false);
    }

    private static Writer writer(StreamingChatModel model, boolean guarded) {
        var builder = AiServices.builder(Writer.class).streamingChatModel(model).chatMemoryProvider(id -> {
            var previous = ((Turn) id).previous;
            var memory = MessageWindowChatMemory.builder().id(id).maxMessages(previous.size() + 2).build();
            memory.set(previous);
            return memory;
        });
        if (guarded) builder.outputGuardrails(new NativeOutputGuardrail())
                // 本 SDK 将初次校验计入上限：2 次校验只允许再请求 1 次。
                .outputGuardrailsConfig(OutputGuardrailsConfig.builder().maxRetries(2).build());
        return builder.build();
    }

    Flux<Chunk> generate(List<ChatMessage> messages, GenerationSession session) {
        String prompt = ((UserMessage) messages.getLast()).singleText();
        return Flux.defer(() -> {
                    if (!session.isRunning()) return Flux.<AiServiceStreamingEvent>empty();
                    var turn = new Turn(messages.subList(0, messages.size() - 1));
                    var scope = guarded ? new GuardedTurnModel(model, session) : null;
                    Writer service = guarded ? writer(scope, true) : plainWriter;
                    return Flux.defer(() -> service.write(turn, prompt)).doFinally(signal -> {
                        if (scope != null) scope.stop();
                        service.evictChatMemory(turn);
                    });
                })
                .<Chunk>handle((event, sink) -> {
                    if (event instanceof AiServiceStreamingEvent.PartialResponseEvent part)
                        sink.next(new Chunk(part.partialResponse().text(), null));
                    else if (event instanceof AiServiceStreamingEvent.FinalResponseEvent end) {
                        var response = end.chatResponse();
                        // SDK 在启用输出护轨时不发送候选稿的分片，只展示最终放行的内容。
                        String text = guarded && response.finishReason() == dev.langchain4j.model.output.FinishReason.STOP
                                ? response.aiMessage().text() : null;
                        sink.next(new Chunk(text, response));
                    }
                });
    }

    // SDK 的护轨会独立订阅纠正请求；外层 Flux 取消时，同一轮的每个模型订阅都必须停止。
    private static final class GuardedTurnModel implements StreamingChatModel {
        private final StreamingChatModel delegate;
        private final GenerationSession session;
        private final Sinks.Empty<Void> stopSignal = Sinks.empty();
        private volatile boolean stopped;

        GuardedTurnModel(StreamingChatModel delegate, GenerationSession session) {
            this.delegate = delegate;
            this.session = session;
        }

        @Override public Flow.Publisher<ChatModelStreamingEvent> chat(ChatRequest request) {
            var events = Flux.defer(() -> stopped || !session.isRunning()
                    ? Flux.<ChatModelStreamingEvent>error(new CancellationException())
                    : JdkFlowAdapter.flowPublisherToFlux(delegate.chat(request)));
            return JdkFlowAdapter.publisherToFlowPublisher(events.takeUntilOther(stopSignal.asMono()));
        }

        @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
        @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }

        void stop() {
            stopped = true;
            stopSignal.tryEmitEmpty();
        }
    }
}
