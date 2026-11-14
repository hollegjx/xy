package com.xy.app.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "我的应用查询参数")
public record AppQueryRequest(
        @Schema(description = "应用名称，模糊匹配；留空查询全部", example = "博客") String appName,
        @Schema(description = "页码，从 1 开始", defaultValue = "1", example = "1")
        @Min(value = 1, message = "页码必须大于 0") Integer current,
        @Schema(description = "每页条数，默认 10，最多 20", defaultValue = "10", example = "10")
        @Min(value = 1, message = "每页至少查询 1 个应用")
        @Max(value = 20, message = "每页最多查询 20 个应用") Integer pageSize) {

    public AppQueryRequest {
        appName = appName == null ? "" : appName.strip();
        current = current == null ? 1 : current;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
