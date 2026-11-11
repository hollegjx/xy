package com.xy.chat.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@Schema(description = "消息历史查询参数")
public record ChatHistoryQueryRequest(
        @Schema(description = "向前查询的消息 ID，不包含该消息；首次查询不填", example = "101")
        @Positive(message = "消息游标必须大于 0") Long beforeId,
        @Schema(description = "每页条数，默认 10，最多 20", defaultValue = "10", example = "10")
        @Min(value = 1, message = "每页至少查询 1 条消息")
        @Max(value = 20, message = "每页最多查询 20 条消息") Integer pageSize) {

    public ChatHistoryQueryRequest {
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
