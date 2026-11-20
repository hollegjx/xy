package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class VueArchitectureDiagram {
    static final String COMPONENT = "src/components/XyArchitecture.vue";
    static final String DATA = "src/components/xy-architecture.json";
    static final ToolSpecification TOOL = ToolSpecification.builder().name("drawArchitecture")
            .description("按用户描述绘制本地系统架构图，只在需要说明模块关系、还未写页面时使用。")
            .parameters(JsonObjectSchema.builder()
                    .addProperty("nodes", JsonArraySchema.builder().description("按阅读顺序列出不重复的模块名")
                            .items(JsonStringSchema.builder().build()).build())
                    .addProperty("edges", JsonArraySchema.builder().description("有向关系，from 和 to 均使用 nodes 中的名称")
                            .items(JsonObjectSchema.builder().addStringProperty("from", "起点")
                                    .addStringProperty("to", "终点").addStringProperty("label", "关系说明（可省略）")
                                    .required("from", "to").additionalProperties(false).build()).build())
                    .required("nodes", "edges").additionalProperties(false).build()).build();

    record Edge(String from, String to, String label) {}
    record Diagram(List<String> nodes, List<Edge> edges) {}

    static String findError(ObjectMapper mapper, Map<String, String> sources) {
        if (!VueComponentReferences.references(sources, DATA)) return null;
        try {
            parse(mapper, sources.get(DATA));
            return null;
        } catch (IllegalArgumentException invalid) {
            return "架构图 xy-architecture.json 无效：" + invalid.getMessage() + "。请同步修改节点和连线后重试";
        }
    }

    static Diagram parse(ObjectMapper mapper, String arguments) {
        JsonNode input;
        try {
            input = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("需要包含 nodes 和 edges 的 JSON 对象");
        }
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
