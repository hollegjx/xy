package com.xy.user.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

@Schema(description = "管理员修改用户信息请求，当前仅支持昵称")
public record UserUpdateRequest(
        @NotBlank(message = "昵称不能为空")
        @CodePointLength(max = 128, message = "昵称长度不能超过 128 个字符")
        @Schema(description = "昵称，最多 128 个字符", example = "星云成员", maxLength = 128,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String userName) {
}
