package com.xy.user.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户信息，不包含密码散列")
public record UserVO(
        @Schema(description = "服务端生成的用户 ID", example = "1") String id,
        @Schema(description = "登录账号", example = "xy_demo") String userAccount,
        @Schema(description = "昵称", example = "xy_demo") String userName,
        @Schema(description = "角色：user 普通用户 / admin 管理员", example = "user") String userRole) {
}
