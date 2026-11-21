package com.xy.generation.service.impl;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 结构化 AI Service 在本次会话的流式连接上执行；内部报告不作为代码增量发送。 */
final class WorkflowNodeModel {
    static ChatModel adapt(StreamingChatModel upstream, GenerationSession session, CompletableFuture<?> node,
            Set<Capability> capabilities, Consumer<Throwable> onFailure) {
        return new ChatModel() {
            @Override public Set<Capability> supportedCapabilities() { return capabilities; }
            @Override public CompletableFuture<ChatResponse> doChatAsync(ChatRequest request) {
                var response = new CompletableFuture<ChatResponse>();
                node.whenComplete((ignored, error) -> { if (node.isCancelled()) response.cancel(false); });
                Consumer<Throwable> failed = error -> {
                    onFailure.accept(error);
                    response.completeExceptionally(error);
                };
                if (session.isRunning() && !response.isDone()) {
                    try { upstream.chat(request, session.modelStep(false, ignored -> {}, response::complete, failed)); }
                    catch (RuntimeException error) { failed.accept(error); }
                } else response.cancel(false);
                return response;
            }
        };
    }

    private WorkflowNodeModel() {}
}
