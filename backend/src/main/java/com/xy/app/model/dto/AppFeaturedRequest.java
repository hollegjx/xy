package com.xy.app.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "管理员设置应用精选状态")
public record AppFeaturedRequest(
        @Schema(description = "true 设为精选，false 取消精选", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择是否设为精选") Boolean featured) {
}
