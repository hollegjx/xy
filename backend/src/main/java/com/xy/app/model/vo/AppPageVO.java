package com.xy.app.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "我的应用分页结果")
public record AppPageVO(
        @Schema(description = "本页应用，按创建时间和 ID 倒序排列") List<AppVO> records,
        @Schema(description = "符合查询条件的应用总数", example = "21") long total,
        @Schema(description = "当前页码", example = "1") long current,
        @Schema(description = "每页条数", example = "10") long pageSize) {
}
