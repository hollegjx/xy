package com.xy.app.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

@Schema(description = "修改应用信息请求")
public record AppUpdateRequest(
        @NotBlank(message = "应用名称不能为空")
        @CodePointLength(max = 128, message = "应用名称不能超过 128 个字符")
        @Schema(description = "应用名称，最多 128 个字符", maxLength = 128,
                example = "我的个人博客", requiredMode = Schema.RequiredMode.REQUIRED)
        String appName) {
}
