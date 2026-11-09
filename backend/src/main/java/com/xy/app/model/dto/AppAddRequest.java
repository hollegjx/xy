package com.xy.app.model.dto;

import com.xy.app.model.enums.CodeGenTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

@Schema(description = "创建应用请求")
public record AppAddRequest(
        @NotBlank(message = "初始需求不能为空")
        @CodePointLength(max = 16383, message = "初始需求不能超过 16383 个字符")
        @Schema(description = "初始需求，最多 16383 个字符；名称取前 12 个字符", maxLength = 16383,
                example = "生成一个简洁的个人作品展示网站", requiredMode = Schema.RequiredMode.REQUIRED)
        String initPrompt,
        @Schema(description = "生成模式：html 单 HTML / multi_file 多文件；省略或为 null 时默认多文件",
                allowableValues = {"html", "multi_file"}, defaultValue = "multi_file", example = "multi_file")
        CodeGenTypeEnum codeGenType) {
}
