package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;

final class VueIllustrations {
    static final String COMPONENT = "src/components/XyIllustration.vue";
    private static final Map<String, String> THEMES = Map.of(
            "reading", "学习阅读", "teamwork", "团队协作", "analytics", "数据分析");
    static final ToolSpecification TOOL = ToolSpecification.builder().name("selectIllustration")
            .description("从 XY 的内置库选一幅插画。仅在新页面需要匹配主题的插画时、写文件前调用。")
            .parameters(JsonObjectSchema.builder().addEnumProperty("theme", THEMES.keySet().stream().sorted().toList(),
                    "reading 学习阅读、teamwork 团队协作、analytics 数据分析")
                    .required("theme").additionalProperties(false).build()).build();

    record Illustration(String label, String source) {}

    static Optional<Illustration> select(ObjectMapper mapper, String arguments) throws IOException {
        String theme;
        try {
            var args = mapper.readTree(arguments);
            theme = args == null ? "" : args.path("theme").asText("");
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
        String label = THEMES.get(theme);
        if (label == null) return Optional.empty();
        var asset = new ClassPathResource("illustrations/" + theme + ".vue");
        return Optional.of(new Illustration(label, asset.getContentAsString(StandardCharsets.UTF_8)));
    }

    private VueIllustrations() {}
}
