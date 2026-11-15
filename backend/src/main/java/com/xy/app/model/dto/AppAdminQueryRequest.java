package com.xy.app.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@Schema(description = "管理员应用查询参数")
public record AppAdminQueryRequest(
        @Schema(description = "应用名称，模糊匹配；留空不筛选", example = "博客") String appName,
        @Schema(description = "创建者 ID，必须大于 0；省略时查询所有创建者", example = "84")
        @Positive(message = "创建者 ID 必须大于 0") Long userId,
        @Schema(description = "页码，从 1 开始", defaultValue = "1", example = "1")
        @Min(value = 1, message = "页码必须大于 0") Integer current,
        @Schema(description = "每页条数，默认 10", defaultValue = "10", example = "10")
        @Min(value = 1, message = "每页至少查询 1 个应用") Integer pageSize) {

    public AppAdminQueryRequest {
        appName = appName == null ? "" : appName.strip();
        current = current == null ? 1 : current;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
