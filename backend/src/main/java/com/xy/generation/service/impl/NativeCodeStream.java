package com.xy.generation.service.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.AiServiceStreamingEvent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import java.util.List;
import reactor.core.publisher.Flux;

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
    private final Writer writer;
    private final boolean guarded;

    NativeCodeStream(StreamingChatModel model, boolean guarded) {
        this.guarded = guarded;
        var builder = AiServices.builder(Writer.class).streamingChatModel(model).chatMemoryProvider(id -> {
            var previous = ((Turn) id).previous;
            var memory = MessageWindowChatMemory.builder().id(id).maxMessages(previous.size() + 2).build();
            memory.set(previous);
            return memory;
        });
        if (guarded) builder.outputGuardrails(new NativeOutputGuardrail())
                // 本 SDK 将初次校验计入上限：2 次校验只允许再请求 1 次。
                .outputGuardrailsConfig(OutputGuardrailsConfig.builder().maxRetries(2).build());
        writer = builder.build();
    }

    Flux<Chunk> generate(List<ChatMessage> messages, GenerationSession session) {
        var turn = new Turn(messages.subList(0, messages.size() - 1));
        String prompt = ((UserMessage) messages.getLast()).singleText();
        return Flux.defer(() -> session.isRunning() ? writer.write(turn, prompt) : Flux.<AiServiceStreamingEvent>empty())
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
                })
                .doFinally(signal -> writer.evictChatMemory(turn));
    }
}
