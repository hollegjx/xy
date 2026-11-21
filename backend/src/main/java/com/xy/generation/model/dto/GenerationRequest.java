package com.xy.generation.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

@Schema(description = "生成网站请求；首次生成传入应用的初始需求")
public record GenerationRequest(
        @Schema(description = "本次网站需求，最多 16383 个 Unicode 码点；内容检查未通过时返回 400，不建立生成流",
                maxLength = 16383, example = "创建一个简洁的摄影作品集", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "生成需求不能为空")
        @CodePointLength(max = 16383, message = "生成需求不能超过 16383 个字符")
        String prompt,
        @Schema(description = "生成后进行 AI 质检，必要时修复一次；支持全部三种生成模式，默认关闭", example = "false")
        Boolean qualityCheck) {
    public GenerationRequest(String prompt) { this(prompt, false); }
}
