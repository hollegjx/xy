package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;

final class NativeVectorAssets {
    private static final Pattern MARKER = Pattern.compile("data-xy-(?:logo|architecture)", Pattern.CASE_INSENSITIVE);
    static final String HTML_HINT = """
            用户需要品牌标志或系统关系图时，可以在 index.html 正文指定设计，系统会在原有文字前嵌入本地 SVG。
            Logo 示例：<span data-xy-logo='{"name":"星云","layers":[{"shape":"circle","color":"#305864","coordinates":[50,50,35]}]}'>星云</span>。
            按品牌设计 layers：circle 为 [圆心x,圆心y,半径]，line 和 polygon 为依次排列的 x,y，坐标 0–100，颜色为六位十六进制。
            架构示例：<figure data-xy-architecture='{"nodes":["浏览器","网关"],"edges":[{"from":"浏览器","to":"网关","label":"请求"}]}'>
            <figcaption>浏览器向网关发出请求</figcaption></figure>。节点名唯一，连线使用实际节点名；label 可省略。
            示例内容须换成用户自己的品牌或系统。按 HTML 属性规则转义 JSON，保留有意义的品牌名或关系说明；不需要图时省略标记。""";

    static String embed(String html, ObjectMapper mapper, Consumer<String> progress, NativeAssetImages images) {
        if (!MARKER.matcher(html).find()) return html;
        var body = Parser.htmlParser().setTrackPosition(true).parseInput(html, "").body();
        var slots = body.select("span[data-xy-logo], figure[data-xy-architecture]").stream()
                .filter(slot -> slot.sourceRange().isTracked())
                .sorted(Comparator.comparingInt(slot -> slot.sourceRange().startPos())).toList();
        if (slots.isEmpty()) return html;
        var out = new StringBuilder();
        var notices = new LinkedHashSet<String>();
        int offset = 0;
        for (var slot : slots) {
            boolean logo = slot.normalName().equals("span");
            String attribute = logo ? "data-xy-logo" : "data-xy-architecture";
            String design = slot.attr(attribute);
            var svg = logo ? VueBrandLogo.renderSvg(mapper, design) : architecture(mapper, design, images);
            var range = slot.sourceRange();
            // 只改占位元素的起始标签。视觉样式、按钮目标、说明文字与页面脚本都保持原样。
            slot.removeAttr(attribute);
            out.append(html, offset, range.startPos()).append('<').append(slot.tagName()).append(slot.attributes().html())
                    .append('>').append(svg.orElse(""));
            offset = range.endPos();
            String label = logo ? "品牌 Logo" : "架构图";
            notices.add(svg.isPresent() ? "已嵌入" + label : label + "：设计无效，保留页面文字");
        }
        progress.accept("\n" + String.join("；", notices) + "。\n");
        return out.append(html, offset, html.length()).toString();
    }

    private static Optional<String> architecture(ObjectMapper mapper, String design, NativeAssetImages images) {
        try {
            return Optional.of(images.diagram(VueArchitectureDiagram.parse(mapper, design)));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private NativeVectorAssets() {}
}
