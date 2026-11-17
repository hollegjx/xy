package com.xy.app.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "应用详情")
public record AppVO(
        @Schema(description = "服务端生成的应用 ID", example = "55") String id,
        @Schema(description = "创建者 ID", example = "42") String userId,
        @Schema(description = "应用名称", example = "个人博客") String appName,
        @Schema(description = "初始需求", example = "生成一个简洁的个人博客") String initPrompt,
        @Schema(description = "生成模式：html 单 HTML / multi_file 多文件 / vue_project Vue 工程源码", example = "multi_file") String codeGenType,
        @Schema(description = "创建时间", example = "2026-11-09T10:00:00") LocalDateTime createTime,
        @Schema(description = "更新时间", example = "2026-11-09T10:00:00") LocalDateTime updateTime,
        @Schema(description = "最近发布信息；尚未发布时为 null") AppDeployVO deployment) {
}
