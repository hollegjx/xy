package com.xy.chat.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "消息历史分页结果")
public record ChatHistoryPageVO(
        @Schema(description = "本页消息，按 ID 从旧到新排列", requiredMode = Schema.RequiredMode.REQUIRED) List<ChatHistoryVO> records,
        @Schema(description = "是否还有更早的消息", example = "true", requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
        @Schema(description = "下一页 beforeId；没有更早消息时为 null", example = "101", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String nextCursor) {
}
