package com.xy.generation.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "SSE 事件数据；事件名为 started / delta / completed / failed / cancelled")
public record GenerationEventVO(
        @Schema(description = "生成 ID，与 AI 消息 ID 相同", example = "101") String generationId,
        @Schema(description = "运行状态：running / completed / failed / cancelled", example = "running") String status,
        @Schema(description = "delta 事件的增量源码") String text,
        @Schema(description = "failed 或 cancelled 事件的可读说明") String message) {
}
