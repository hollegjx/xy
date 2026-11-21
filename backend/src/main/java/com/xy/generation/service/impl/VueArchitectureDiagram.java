package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Element;
import org.springframework.core.io.ClassPathResource;

final class VueArchitectureDiagram {
    record Slot(int number) {
        private String suffix() { return number == 1 ? "" : "-" + number; }
        String component() { return "src/components/XyArchitecture" + (number == 1 ? "" : number) + ".vue"; }
        String data() { return "src/components/xy-architecture" + suffix() + ".json"; }
        String image() { return "src/components/xy-mermaid-image" + suffix() + ".json"; }
        String renderer(boolean mermaid) throws IOException {
            String source = mermaid ? ArtworkProviders.MERMAID_COMPONENT : bundledRenderer();
            return source.replace("./xy-architecture.json", data().replace("src/components/", "./"))
                    .replace("./xy-mermaid-image.json", image().replace("src/components/", "./"));
        }
    }
    static final String DATA = new Slot(1).data();
    static final ToolSpecification TOOL = ToolSpecification.builder().name("drawArchitecture")
            .description("写页面前为每处系统架构或流程图提供节点和有向关系，可同批或分次请求多张图。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("purpose", "本图在页面中的用途或位置；修正无效关系时沿用原用途")
                    .addProperty("nodes", JsonArraySchema.builder().description("按阅读顺序列出不重复的模块名")
                            .items(JsonStringSchema.builder().build()).build())
                    .addProperty("edges", JsonArraySchema.builder().description("有向关系，from 和 to 均使用 nodes 中的名称")
                            .items(JsonObjectSchema.builder().addStringProperty("from", "起点")
                                    .addStringProperty("to", "终点").addStringProperty("label", "关系说明（可省略）")
                                    .required("from", "to").additionalProperties(false).build()).build())
                    .required("nodes", "edges").additionalProperties(false).build()).build();

    record Edge(String from, String to, String label) {
        Edge { if (label == null) label = ""; }
    }
    record Diagram(List<String> nodes, List<Edge> edges) {}

    static String bundledRenderer() throws IOException {
        return new ClassPathResource("diagrams/XyArchitecture.vue").getContentAsString(StandardCharsets.UTF_8);
    }

    static boolean containsLegacyRenderer(Map<String, String> sources) {
        return containsLegacyRenderer(sources, new Slot(1));
    }

    static boolean containsLegacyRenderer(Map<String, String> sources, Slot slot) {
        for (var file : sources.entrySet()) {
            if (!file.getKey().endsWith(".vue")) continue;
            var blocks = VueComponentReferences.parseVueBlocks(file.getValue());
            if (blocks.select("template svg").stream().noneMatch(VueArchitectureDiagram::drawsNodesAndEdges)) continue;
            if (blocks.children().stream().anyMatch(block -> block.normalName().equals("script")
                    && VueComponentReferences.readsDefaultImportFields(file.getKey(), block.data(), sources,
                            slot.data(), Set.of("nodes", "edges")))) return true;
        }
        return false;
    }

    static boolean containsLegacyImageRenderer(Map<String, String> sources, Slot slot) {
        for (var file : sources.entrySet()) {
            if (!file.getKey().endsWith(".vue")) continue;
            var blocks = VueComponentReferences.parseVueBlocks(file.getValue());
            for (var image : blocks.select("template img")) {
                String src = binding(image, "src"), alt = binding(image, "alt");
                if (src.isBlank() || alt.isBlank() || image.closest("[v-pre]") != null) continue;
                // 旧 Kroki 组件在同一张图上绑定成图和节点说明；同页分别使用两个 JSON 不代表封面属于绘图工具。
                for (var script : blocks.children()) {
                    if (script.normalName().equals("script")
                            && VueComponentReferences.readsDefaultImportFields(file.getKey(), script.data(), sources,
                                    slot.image(), Set.of("src"), src)
                            && VueComponentReferences.readsDefaultImportFields(file.getKey(), script.data(), sources,
                                    slot.data(), Set.of("nodes"), alt)) return true;
                }
            }
        }
        return false;
    }

    private static String binding(Element element, String name) {
        return element.hasAttr(":" + name) ? element.attr(":" + name) : element.attr("v-bind:" + name);
    }

    private static boolean drawsNodesAndEdges(Element svg) {
        // 旧组件用同一个 SVG 循环绘制节点与连线路径；颜色、样式表和组件名均可修改。
        return svg.selectFirst("g[v-for~=(?:in|of)\\s+nodes\\s*$]") != null
                && svg.select("path[v-for~=(?:in|of)\\s+edges\\s*$]").stream()
                        .anyMatch(path -> path.hasAttr(":d") || path.hasAttr("v-bind:d"));
    }

    static String findError(ObjectMapper mapper, Map<String, String> sources, Set<Slot> generated, Set<Slot> platformImages) {
        String missing = VueComponentReferences.missingDiagramComponent(sources);
        if (missing != null) return "页面仍引用不存在的 " + missing + "，请改用文字说明或补全组件后重试";
        for (var slot : generated) {
            if (!VueComponentReferences.references(sources, slot.data())
                    && !(platformImages.contains(slot) && VueComponentReferences.references(sources, slot.image()))) continue;
            try {
                parse(mapper, sources.get(slot.data()));
            } catch (IllegalArgumentException invalid) {
                return "架构图 " + slot.data() + " 无效：" + invalid.getMessage() + "。请同步修改节点和连线后重试";
            }
        }
        return null;
    }

    static Diagram parse(ObjectMapper mapper, String arguments) {
        JsonNode input;
        try {
            input = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("需要包含 nodes 和 edges 的 JSON 对象");
        }
        return parse(input);
    }

    static Diagram parse(JsonNode input) {
        if (input == null || !input.path("nodes").isArray() || !input.path("edges").isArray()
                || input.path("edges").isEmpty()) throw new IllegalArgumentException("需要节点和至少一条有向连线");
        // 与组件的 Map 查找一致：节点和连线端点以文件里的原文精确匹配。
        var nodes = new LinkedHashSet<String>();
        for (var node : input.get("nodes")) {
            String name = text(node);
            if (name.isBlank() || !nodes.add(name)) throw new IllegalArgumentException("节点名称不能为空或重复");
        }
        var edges = new ArrayList<Edge>();
        for (var edge : input.get("edges")) {
            String from = text(edge.path("from")), to = text(edge.path("to"));
            if (!nodes.contains(from) || !nodes.contains(to)) throw new IllegalArgumentException("连线的起点和终点必须出现在节点清单中");
            if (edge.has("label") && !edge.path("label").isTextual()) throw new IllegalArgumentException("关系说明必须是文本");
            edges.add(new Edge(from, to, text(edge.path("label"))));
        }
        return new Diagram(List.copyOf(nodes), List.copyOf(edges));
    }

    private static String text(JsonNode value) { return value.isTextual() ? value.textValue() : ""; }

    private VueArchitectureDiagram() {}
}
