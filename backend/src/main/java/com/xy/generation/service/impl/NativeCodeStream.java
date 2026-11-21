package com.xy.generation.service.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import java.util.List;
import reactor.core.publisher.Flux;

/** 对外保留 XY 的 SSE 事件；模型先送分片，最后单独送结束原因和完整候选稿。 */
final class NativeCodeStream {
    interface Writer extends ChatMemoryAccess {
        TokenStream write(@MemoryId Object turn, @dev.langchain4j.service.UserMessage String request);
    }

    // 实例标识每次调用。晚到的旧回调只能写旧草稿；已提交记忆由 GenerationMemory 管理。
    private static final class Turn {
        final List<ChatMessage> previous;
        Turn(List<ChatMessage> previous) { this.previous = List.copyOf(previous); }
    }

    record Chunk(String delta, ChatResponse finalResponse) {}
    private final Writer writer;

    NativeCodeStream(StreamingChatModel model) {
        writer = AiServices.builder(Writer.class).streamingChatModel(model).chatMemoryProvider(id -> {
            var previous = ((Turn) id).previous;
            var memory = MessageWindowChatMemory.builder().id(id).maxMessages(previous.size() + 2).build();
            memory.set(previous);
            return memory;
        }).build();
    }

    Flux<Chunk> generate(List<ChatMessage> messages, GenerationSession session) {
        var turn = new Turn(messages.subList(0, messages.size() - 1));
        String prompt = ((UserMessage) messages.getLast()).singleText();
        return Flux.<Chunk>create(sink -> {
            TokenStream tokens;
            synchronized (session) {
                if (!session.isRunning()) { sink.complete(); return; }
                tokens = writer.write(turn, prompt);
            }
            tokens
                    .onPartialResponseWithContext((delta, ctx) -> {
                        session.trackHandle(ctx.streamingHandle());
                        sink.next(new Chunk(delta.text(), null));
                    })
                    .onPartialThinkingWithContext((part, ctx) -> session.trackHandle(ctx.streamingHandle()))
                    .onCompleteResponse(response -> {
                        try { sink.next(new Chunk(null, response)); sink.complete(); }
                        finally { writer.evictChatMemory(turn); } // 取消后迟到的 SDK 回调也会触及记忆。
                    })
                    .onError(sink::error).start();
        }).doFinally(signal -> writer.evictChatMemory(turn));
    }
}
