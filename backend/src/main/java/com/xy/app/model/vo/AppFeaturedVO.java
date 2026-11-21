package com.xy.app.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "访客可见的精选作品，不含私有应用内容")
public record AppFeaturedVO(
        @Schema(description = "服务端生成的应用 ID", example = "55", requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(description = "应用名称", example = "个人博客", requiredMode = Schema.RequiredMode.REQUIRED) String appName,
        @Schema(description = "生成模式：html 单 HTML / multi_file 多文件", example = "multi_file", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"html", "multi_file", "vue_project"}) String codeGenType,
        @Schema(description = "独立站点的公开作品地址", example = "https://sites.example.com/0123456789abcdef0123456789abcdef/", requiredMode = Schema.RequiredMode.REQUIRED, nullable = false) String deployUrl,
        @Schema(description = "当前发布版本的封面地址；没有截图时为 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String coverUrl) {
}
