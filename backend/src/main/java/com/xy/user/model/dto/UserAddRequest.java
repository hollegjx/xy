package com.xy.user.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

@Schema(description = "管理员新增用户请求，仅创建普通用户")
public record UserAddRequest(
        @NotBlank(message = "账号不能为空")
        @CodePointLength(max = 128, message = "账号长度不能超过 128 个字符")
        @Schema(description = "登录账号，最多 128 个字符", example = "xy_member", maxLength = 128,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String userAccount,
        @NotBlank(message = "初始密码不能为空")
        @Schema(description = "新用户的初始密码", example = "Demo123456!", requiredMode = Schema.RequiredMode.REQUIRED)
        String userPassword,
        @CodePointLength(max = 128, message = "昵称长度不能超过 128 个字符")
        @Schema(description = "昵称，最多 128 个字符；省略或空白时使用账号", example = "星云用户", maxLength = 128)
        String userName) {
}
