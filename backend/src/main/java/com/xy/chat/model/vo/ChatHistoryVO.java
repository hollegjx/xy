package com.xy.chat.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "应用消息；AI 消息 ID 同时是生成 ID")
public record ChatHistoryVO(
        @Schema(description = "服务端生成的消息 ID", example = "101") String id,
        @Schema(description = "消息角色：user / assistant", example = "assistant") String messageRole,
        @Schema(description = "已保存的需求或模型输出；运行中的增量内容由 SSE 提供", example = "") String content,
        @Schema(description = "状态：running / completed / failed / cancelled", example = "running") String status,
        @Schema(description = "可读失败原因，无错误时为 null", example = "生成中断，请重试") String errorMessage,
        @Schema(description = "创建时间", example = "2026-11-11T10:00:00") LocalDateTime createTime,
        @Schema(description = "更新时间", example = "2026-11-11T10:00:00") LocalDateTime updateTime) {
}
