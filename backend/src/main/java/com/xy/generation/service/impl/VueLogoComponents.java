package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 一轮 Vue 生成按设计分配组件路径；页面用途只进清单，不交给图像供应方。 */
final class VueLogoComponents {
    static final ToolSpecification TOOL = VueAssetTool.describe(LogoTool.class);
    record Drawing(String path, String brand, CompletableFuture<Optional<String>> source) {}
    record Use(Drawing drawing, String purpose) {}
    private record DrawingKey(VueBrandLogo.DesignKey design, boolean drawable) {}

    private final Map<DrawingKey, Drawing> drawings = new LinkedHashMap<>();
    private final Set<String> written = new HashSet<>();

    Use prepare(String arguments, ObjectMapper mapper, ArtworkProviders artwork, Executor executor) {
        return prepare(ToolExecutionRequest.builder().name(TOOL.name()).arguments(arguments).build(), mapper, artwork, executor);
    }

    Use prepare(ToolExecutionRequest call, ObjectMapper mapper, ArtworkProviders artwork, Executor executor) {
        ObjectNode request = mapper.createObjectNode();
        try {
            var parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.arguments());
            if (parsed instanceof ObjectNode object) request = object;
        } catch (IOException | IllegalArgumentException invalid) { /* 不合规的标志只回退本处文字。 */ }
        String purpose = request.path("purpose").asText("").strip();
        var layers = request.path("layers");
        // 先按原始类型检查绘图约束，避免 SDK 把浮点数或字符串坐标转换为整数。
        if (request.path("name").isTextual() && !request.get("name").textValue().isBlank()
                && (layers.isMissingNode() || layers.isArray() && (layers.isEmpty() || VueBrandLogo.svgLayers(layers).isPresent()))) {
            var result = VueAssetTool.call(new LogoTool(mapper, artwork, executor), call, Use.class);
            if (result.isPresent()) return result.get();
        }
        return reserve(request.retain("name", "brief", "layers"), purpose, mapper, artwork, executor, false);
    }

    private final class LogoTool {
        final ObjectMapper mapper;
        final ArtworkProviders artwork;
        final Executor executor;
        LogoTool(ObjectMapper mapper, ArtworkProviders artwork, Executor executor) {
            this.mapper = mapper; this.artwork = artwork; this.executor = executor;
        }

        @Tool("写源码前为一处品牌标志提供名称、意象和配色。多个品牌可同批分别调用，相同设计在不同位置复用。")
        public Use generateLogo(@P("品牌名称，用作图片的无障碍名称") String name,
                @P("100×100 透明画布，从底到顶依次绘制") List<VueBrandLogo.Layer> layers,
                @P(value = "图形意象或风格，不含用户原始需求或其他个人信息", required = false) String brief,
                @P(value = "标志在页面中的用途或位置，仅用于页面编排", required = false) String purpose) {
            var design = mapper.createObjectNode().put("name", name);
            if (brief != null) design.put("brief", brief);
            if (layers != null) design.set("layers", mapper.valueToTree(layers));
            return reserve(design, purpose == null ? "" : purpose.strip(), mapper, artwork, executor, true);
        }
    }

    private Use reserve(ObjectNode design, String purpose, ObjectMapper mapper, ArtworkProviders artwork, Executor executor, boolean drawable) {
        var drawing = drawings.computeIfAbsent(new DrawingKey(VueBrandLogo.designKey(design), drawable), key -> {
            String brand = key.design().name();
            String path = drawings.isEmpty() ? VueBrandLogo.COMPONENT : "src/components/XyLogo" + (drawings.size() + 1) + ".vue";
            var source = !drawable ? CompletableFuture.completedFuture(Optional.<String>empty()) : CompletableFuture.supplyAsync(() -> {
                String json = design.toString();
                var image = artwork.rendersLogo() ? artwork.logoImage(mapper, json) : Optional.<String>empty();
                return image.map(img -> "<template>" + img + "</template>").or(() -> VueBrandLogo.render(mapper, json));
            }, executor);
            return new Drawing(path, brand, source);
        });
        return new Use(drawing, purpose);
    }

    boolean firstWrite(String path) { return written.add(path); }

    String missingReference(Map<String, String> sources) {
        for (var drawing : drawings.values()) {
            String path = drawing.path();
            if (!sources.containsKey(path) && VueComponentReferences.references(sources, path))
                return "页面仍引用不存在的 " + path.substring(path.lastIndexOf('/') + 1) + "，请改用品牌文字或补全组件后重试";
        }
        return null;
    }
}
