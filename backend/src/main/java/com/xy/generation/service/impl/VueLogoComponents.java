package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 一轮 Vue 生成按设计分配组件路径；页面用途只进清单，不交给图像供应方。 */
final class VueLogoComponents {
    record Drawing(String path, String brand, CompletableFuture<Optional<String>> source) {}
    record Use(Drawing drawing, String purpose) {}

    private final Map<ObjectNode, Drawing> drawings = new LinkedHashMap<>();
    private final Set<String> written = new HashSet<>();

    Use prepare(String arguments, ObjectMapper mapper, ArtworkProviders artwork, Executor executor) {
        ObjectNode request = mapper.createObjectNode();
        try {
            var parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
            if (parsed instanceof ObjectNode object) request = object;
        } catch (IOException | IllegalArgumentException invalid) { /* 不合规的标志只回退本处文字。 */ }
        String purpose = request.path("purpose").asText("").strip();
        var design = request.retain("name", "brief", "layers");
        var drawing = drawings.computeIfAbsent(design, key -> {
            String brand = key.path("name").isTextual() ? key.get("name").textValue().strip() : "";
            String path = drawings.isEmpty() ? VueBrandLogo.COMPONENT : "src/components/XyLogo" + (drawings.size() + 1) + ".vue";
            var source = CompletableFuture.supplyAsync(() -> {
                if (brand.isEmpty()) return Optional.<String>empty();
                String json = key.toString();
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
