package com.xy.app.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "应用发布结果")
public record AppDeployVO(
        @Schema(description = "独立静态站点的分享地址；重新发布沿用此地址") String deployUrl,
        @Schema(description = "已发布的成功生成版本 ID", example = "101") String publishedVersion,
        @Schema(description = "本次发布时间") LocalDateTime deployedAt) {
}
