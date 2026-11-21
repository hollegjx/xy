package com.xy.user.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

@Schema(description = "用户注册请求")
public record UserRegisterRequest(
        @NotBlank(message = "账号不能为空")
        @CodePointLength(max = 128, message = "账号长度不能超过 128 个字符")
        @Schema(description = "登录账号，最多 128 个字符", example = "xy_demo", maxLength = 128,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String userAccount,
        @NotBlank(message = "密码不能为空")
        @Schema(description = "密码", example = "Demo123456!", requiredMode = Schema.RequiredMode.REQUIRED)
        String userPassword,
        @NotBlank(message = "确认密码不能为空")
        @Schema(description = "再次输入密码", example = "Demo123456!", requiredMode = Schema.RequiredMode.REQUIRED)
        String checkPassword) {
}
