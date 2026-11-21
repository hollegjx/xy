package com.xy.health.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "后端与数据库健康状态")
public record HealthVO(
        @Schema(description = "连接成功时为 UP", example = "UP", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"UP"}) String status,
        @Schema(description = "数据库实际返回的当前库名", example = "xy", requiredMode = Schema.RequiredMode.REQUIRED) String database) {
}
