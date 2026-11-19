package com.xy.app.service.impl;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class GenerationModeRouter {

    private static final String INSTRUCTIONS = """
            根据用户希望制作的网站，选择足够完成需求的最简单生成模式。
            html：静态介绍、邀请函、简单单页；multi_file：需要样式和少量脚本交互的原生网站；
            vue_project：多视图、多个复用组件或较复杂的交互状态。考虑用户明确的技术要求。
            只输出 html、multi_file 或 vue_project 其中一个，不写代码、解释或 Markdown。
            """;
    private final ObjectProvider<ChatModel> models;

    public GenerationModeRouter(ObjectProvider<ChatModel> models) {
        this.models = models;
    }

    public CodeGenTypeEnum select(AppAddRequest request) {
        if (request.codeGenType() == null) return CodeGenTypeEnum.MULTI_FILE;
        if (!request.requestsAutomaticMode()) return CodeGenTypeEnum.fromValue(request.codeGenType());
        ChatModel model = models.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "尚未配置自动选择，请手动选择生成模式");
        }
        try {
            String choice = model.chat(SystemMessage.from(INSTRUCTIONS), UserMessage.from(request.initPrompt())).aiMessage().text();
            return CodeGenTypeEnum.fromValue(choice == null ? null : choice.strip());
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "自动选择生成模式失败，请重试或手动选择");
        }
    }
}
