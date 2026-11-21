package com.xy.generation.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class GenerationQualityReviewer {
    record Report(@JsonProperty(required = true) boolean isValid,
                  @JsonProperty(required = true) List<String> errors,
                  @JsonProperty(required = true) List<String> suggestions) {
        Report {
            errors = List.copyOf(errors);
            suggestions = List.copyOf(suggestions);
            if (isValid != errors.isEmpty()) throw new IllegalArgumentException("检查结论与问题列表不一致");
        }
    }

    interface Reviewer {
        @SystemMessage(fromResource = "prompts/generation-quality.txt")
        @UserMessage("{{context}}")
        CompletableFuture<Result<Report>> check(@V("mode") String mode, @V("context") String context);
    }

    static CompletableFuture<Report> check(StreamingChatModel upstream, GenerationSession session,
            CompletableFuture<?> node, ObjectMapper mapper, String mode, String context) {
        // AI Service 生成请求、解析 Report。复用本次生成模型的传输，使工作台在质检中也能取消上游。
        var response = new CompletableFuture<ChatResponse>();
        node.whenComplete((ignored, error) -> { if (node.isCancelled()) response.cancel(false); });
        var transport = new ChatModel() {
            @Override public Set<Capability> supportedCapabilities() { return upstream.supportedCapabilities(); }
            @Override public CompletableFuture<ChatResponse> doChatAsync(ChatRequest request) {
                if (session.isRunning() && !response.isDone()) {
                    try {
                        upstream.chat(request, session.modelStep(false, response::complete));
                    } catch (RuntimeException error) {
                        session.onError(error);
                    }
                } else response.cancel(false);
                return response;
            }
        };
        return AiServices.builder(Reviewer.class).chatModel(transport).build().check(mode, context)
                .thenApply(result -> {
                    // SDK 可宽松读取带围栏或可强转的 JSON；工作台只接受完整且字段类型准确的检查报告。
                    if (result.finishReason() != FinishReason.STOP) throw new IllegalArgumentException("检查报告不完整");
                    try {
                        var raw = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(result.finalResponse().aiMessage().text());
                        if (raw instanceof ObjectNode obj && obj.retain("isValid", "errors", "suggestions")
                                .equals(mapper.valueToTree(result.content()))) return result.content();
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                        // 不在异常或前端提示里回显模型原文。
                    }
                    throw new IllegalArgumentException("检查报告格式错误");
                });
    }
}
