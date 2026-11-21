package com.xy.user.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(description = "管理员用户查询参数")
public record UserQueryRequest(
        @Schema(description = "登录账号，模糊匹配；留空查询全部", example = "xy_demo") String userAccount,
        @Schema(description = "页码，从 1 开始", defaultValue = "1", example = "1")
        @Min(value = 1, message = "页码必须大于 0") Integer current,
        @Schema(description = "每页条数，默认 10", defaultValue = "10", example = "10")
        @Min(value = 1, message = "每页至少查询 1 个用户") Integer pageSize) {

    public UserQueryRequest {
        userAccount = userAccount == null ? "" : userAccount.strip();
        current = current == null ? 1 : current;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
