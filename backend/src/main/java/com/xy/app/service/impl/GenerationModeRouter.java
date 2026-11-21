package com.xy.app.service.impl;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import com.xy.manager.AiModelMetrics;
import com.xy.manager.PromptInputGuard;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class GenerationModeRouter {

    private static final String INSTRUCTIONS = """
            根据用户希望制作的网站，选择足够完成需求的最简单生成模式。
            html：静态介绍、邀请函、简单单页；multi_file：需要样式和少量脚本交互的原生网站；
            vue_project：多视图、多个复用组件或较复杂的交互状态。考虑用户明确的技术要求。
            按要求返回对应的枚举值。
            """;
    interface Classifier {
        @SystemMessage(INSTRUCTIONS)
        @UserMessage("{{request}}")
        CodeGenTypeEnum classify(@V("request") String prompt);
    }

    private final ObjectProvider<ChatModel> models;

    public GenerationModeRouter(ObjectProvider<ChatModel> models) {
        this.models = models;
    }

    public CodeGenTypeEnum select(AppAddRequest request, Long userId) {
        if (request.codeGenType() == null) return CodeGenTypeEnum.MULTI_FILE;
        if (!request.requestsAutomaticMode()) return CodeGenTypeEnum.fromValue(request.codeGenType());
        // 自动选型先于应用入库，此处没有 appId。
        ChatModel model = AiModelMetrics.forClient(userId, null, models::getIfAvailable);
        if (model == null) {
            PromptInputGuard.check(request.initPrompt());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "尚未配置自动选择，请手动选择生成模式");
        }
        try {
            return AiServices.builder(Classifier.class).chatModel(model).inputGuardrails(new PromptInputGuard())
                    .build().classify(request.initPrompt());
        } catch (InputGuardrailException rejected) {
            throw PromptInputGuard.rejection(rejected);
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "自动选择生成模式失败，请重试或手动选择");
        }
    }
}
