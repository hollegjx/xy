package com.xy.generation.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 检查一份候选源码并返回结构化报告。调用方提供可停止的模型连接。 */
public final class GenerationQualityReviewer {
    private GenerationQualityReviewer() {}

    public record Report(@JsonProperty(required = true) boolean isValid,
                         @JsonProperty(required = true) List<String> errors,
                         @JsonProperty(required = true) List<String> suggestions) {
        public Report {
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

    public static CompletableFuture<Report> check(ChatModel model, ObjectMapper mapper, String mode, String context) {
        return AiServices.builder(Reviewer.class).chatModel(model).build().check(mode, context)
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
