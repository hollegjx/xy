package com.xy.generation.service.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
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
    enum Theme {
        analytics("数据分析"), reading("学习阅读"), teamwork("团队协作");
        final String label;
        Theme(String label) { this.label = label; }

        Illustration load() throws IOException {
            var asset = new ClassPathResource("illustrations/" + name() + ".svg");
            return new Illustration(label, asset.getContentAsString(StandardCharsets.UTF_8));
        }
    }
    static final ToolSpecification TOOL = VueAssetTool.describe(ThemeIllustrations.class);

    record Illustration(String label, String svg) {
        String vueSource() { return "<template>\n" + svg + "</template>\n"; }
    }

    record Embedded(String html, List<String> labels) {}

    record Selection(String theme, String purpose, Illustration illustration) {}

    @Tool("为新页面的一处用途选择匹配的内置插画。可同批或分次调用，相同主题复用组件。")
    public Selection selectIllustration(@P("reading 学习阅读、teamwork 团队协作、analytics 数据分析") Theme theme,
            @P(value = "插画在页面中的用途或位置", required = false) String purpose) throws IOException {
        return theme == null ? null : new Selection(theme.name(), purpose == null ? "" : purpose.strip(), theme.load());
    }

    static Optional<Selection> select(ToolExecutionRequest call) {
        return VueAssetTool.call(new ThemeIllustrations(), call, Selection.class);
    }

    static Optional<Illustration> load(String theme) throws IOException {
        if (theme == null) return Optional.empty();
        try { return Optional.of(Theme.valueOf(theme).load()); }
        catch (IllegalArgumentException unknown) { return Optional.empty(); }
    }

    static Embedded embedInBody(String html) throws IOException {
        return embedInBody(html, null);
    }

    static Embedded embedInBody(String html, Map<String, Illustration> prepared) throws IOException {
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
            String theme = slot.getData().strip().substring(SLOT.length());
            var illustration = prepared == null ? load(theme) : Optional.ofNullable(prepared.get(theme));
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
