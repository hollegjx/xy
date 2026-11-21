package com.xy.app.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

@Schema(description = "创建应用请求")
public record AppAddRequest(
        @NotBlank(message = "初始需求不能为空")
        @CodePointLength(max = 16383, message = "初始需求不能超过 16383 个字符")
        @Schema(description = "初始网站需求，最多 16383 个字符；名称取前 12 个字符。内容检查未通过时返回 400，不创建应用", maxLength = 16383,
                example = "生成一个简洁的个人作品展示网站", requiredMode = Schema.RequiredMode.REQUIRED)
        String initPrompt,
        @Pattern(regexp = "auto|html|multi_file|vue_project", message = "生成模式无效")
        @Schema(description = "auto 按初始需求自动选择；也可直接指定生成模式；省略时默认多文件",
                allowableValues = {"auto", "html", "multi_file", "vue_project"}, defaultValue = "multi_file", example = "auto")
        String codeGenType) {

    public boolean requestsAutomaticMode() {
        return "auto".equals(codeGenType);
    }
}
