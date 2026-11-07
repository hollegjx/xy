package com.xy.user.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "用户登录请求")
public record UserLoginRequest(
        @NotBlank(message = "账号不能为空")
        @Schema(description = "登录账号", example = "xy_demo", requiredMode = Schema.RequiredMode.REQUIRED)
        String userAccount,
        @NotBlank(message = "密码不能为空")
        @Schema(description = "密码", example = "Demo123456!", requiredMode = Schema.RequiredMode.REQUIRED)
        String userPassword) {
}
