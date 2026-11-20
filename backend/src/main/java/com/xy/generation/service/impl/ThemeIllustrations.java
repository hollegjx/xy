package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jsoup.nodes.Comment;
import org.jsoup.parser.Parser;
import org.springframework.core.io.ClassPathResource;

final class ThemeIllustrations {
    static final String VUE_COMPONENT = "src/components/XyIllustration.vue";
    private static final String SLOT = "xy:illustration:";
    static final String HTML_HINT = """

            可选的本地主题插画：学习阅读 <!--xy:illustration:reading-->，团队协作 <!--xy:illustration:teamwork-->，
            数据分析 <!--xy:illustration:analytics-->。用户需要且主题匹配时，在 index.html 正文的合适位置放对应的 HTML 注释，
            系统会嵌入真实 SVG。不要在 JavaScript 或 CSS 中放占位；用户不要插画或没有合适主题就省略。""";
    private static final Map<String, String> THEMES = Map.of(
            "reading", "学习阅读", "teamwork", "团队协作", "analytics", "数据分析");
    static final ToolSpecification TOOL = ToolSpecification.builder().name("selectIllustration")
            .description("从 XY 的内置库选一幅插画。仅在新页面需要匹配主题的插画时、写文件前调用。")
            .parameters(JsonObjectSchema.builder().addEnumProperty("theme", THEMES.keySet().stream().sorted().toList(),
                    "reading 学习阅读、teamwork 团队协作、analytics 数据分析")
                    .required("theme").additionalProperties(false).build()).build();

    record Illustration(String label, String svg) {
        String vueSource() { return "<template>\n" + svg + "</template>\n"; }
    }

    record Embedded(String html, List<String> labels) {}

    static Optional<Illustration> select(ObjectMapper mapper, String arguments) throws IOException {
        String theme;
        try {
            var args = mapper.readTree(arguments);
            theme = args == null ? "" : args.path("theme").asText("");
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
        return load(theme);
    }

    private static Optional<Illustration> load(String theme) throws IOException {
        String label = THEMES.get(theme);
        if (label == null) return Optional.empty();
        var asset = new ClassPathResource("illustrations/" + theme + ".svg");
        return Optional.of(new Illustration(label, asset.getContentAsString(StandardCharsets.UTF_8)));
    }

    static Embedded embedInBody(String html) throws IOException {
        if (!html.contains(SLOT)) return new Embedded(html, List.of());
        var body = Parser.htmlParser().setTrackPosition(true).parseInput(html, "").body();
        var slots = body.nodeStream(Comment.class)
                .filter(c -> c.getData().strip().startsWith(SLOT) && c.sourceRange().isTracked())
                .sorted(Comparator.comparingInt(c -> c.sourceRange().startPos())).toList();
        // 按原文位置替换真正的 HTML 注释，不重排页面，也不改脚本、样式或示例文字里的同名字符串。
        var output = new StringBuilder();
        var labels = new LinkedHashSet<String>();
        int offset = 0;
        for (var slot : slots) {
            var illustration = load(slot.getData().strip().substring(SLOT.length()));
            if (illustration.isEmpty()) continue;
            var range = slot.sourceRange();
            output.append(html, offset, range.startPos()).append(illustration.get().svg().strip());
            labels.add(illustration.get().label());
            offset = range.endPos();
        }
        if (labels.isEmpty()) return new Embedded(html, List.of());
        return new Embedded(output.append(html, offset, html.length()).toString(), List.copyOf(labels));
    }

    private ThemeIllustrations() {}
}
