package com.xy.app.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "管理员可见的应用摘要，不含初始需求、对话或源码")
public record AppAdminVO(
        @Schema(description = "服务端生成的应用 ID", example = "55", requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(description = "创建者 ID", example = "84", requiredMode = Schema.RequiredMode.REQUIRED) String userId,
        @Schema(description = "应用名称", example = "个人博客", requiredMode = Schema.RequiredMode.REQUIRED) String appName,
        @Schema(description = "生成模式：html 单 HTML / multi_file 多文件 / vue_project Vue 工程源码", example = "multi_file", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"html", "multi_file", "vue_project"}) String codeGenType,
        @Schema(description = "是否精选，仅管理员可设置", example = "false", requiredMode = Schema.RequiredMode.REQUIRED) boolean featured,
        @Schema(description = "创建时间", example = "2026-11-15T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createTime,
        @Schema(description = "更新时间", example = "2026-11-15T11:00:00", requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime updateTime,
        @Schema(description = "已有发布信息；未发布时为 null，未配置静态站点时 deployUrl 为 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) AppDeployVO deployment) {
}
