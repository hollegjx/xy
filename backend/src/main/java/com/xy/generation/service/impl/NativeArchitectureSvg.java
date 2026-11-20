package com.xy.generation.service.impl;

import com.xy.generation.service.impl.VueArchitectureDiagram.Diagram;
import java.util.ArrayList;
import java.util.HashMap;
import org.springframework.web.util.HtmlUtils;

// 原生下载没有 Vue 运行时：从已校验的节点与连线直接输出静态 SVG。
final class NativeArchitectureSvg {
    private record Arrow(int from, int to, int startPort, int endPort, int lane, String description, boolean labeled) {}

    static String render(Diagram diagram) {
        var nodes = diagram.nodes();
        var index = new HashMap<String, Integer>();
        int box = 128;
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
            box = Math.max(box, textWidth(nodes.get(i)));
        }
        int[] ports = new int[nodes.size()], top = new int[nodes.size()], height = new int[nodes.size()];
        int left = 0, right = 0, legendWidth = 0, labels = 0;
        var arrows = new ArrayList<Arrow>();
        for (var edge : diagram.edges()) {
            int from = index.get(edge.from()), to = index.get(edge.to());
            // 向前和自环从右侧走，回连从左侧走。每条边有自己的通道，起终点各占一个端口。
            int lane = from <= to ? ++right : -(++left);
            boolean labeled = !edge.label().isEmpty();
            String desc = edge.from() + " → " + edge.to() + (labeled ? "：" + edge.label() : "");
            if (labeled) { labels++; legendWidth = Math.max(legendWidth, textWidth(desc)); }
            int startPort = ++ports[from], endPort = ++ports[to];
            arrows.add(new Arrow(from, to, startPort, endPort, lane, desc, labeled));
        }
        int y = 8;
        for (int i = 0; i < nodes.size(); i++) {
            top[i] = y;
            height[i] = Math.max(40, (ports[i] + 1) * 12);
            y += height[i] + 16;
        }
        int x = 12 + left * 16, width = Math.max(x + box + right * 16 + 12, legendWidth);
        int totalHeight = y + labels * 20;
        var svg = new StringBuilder(("<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" aria-label=\"系统架构图\" width=\"%d\" height=\"%d\" "
                + "viewBox=\"0 0 %d %d\" style=\"display:block;max-width:100%%;height:auto\" font-family=\"system-ui,sans-serif\" font-size=\"14\">")
                .formatted(width, totalHeight, width, totalHeight));
        svg.append("<desc>").append(escape(String.join("；", arrows.stream().map(Arrow::description).toList()))).append("</desc>");
        for (var a : arrows) {
            int sign = Integer.signum(a.lane()), wall = sign > 0 ? x + box : x, track = wall + a.lane() * 16;
            int start = top[a.from()] + height[a.from()] * a.startPort() / (ports[a.from()] + 1);
            int end = top[a.to()] + height[a.to()] * a.endPort() / (ports[a.to()] + 1);
            // 箭头尖端落在目标节点一侧，无须 marker ID，同一页可有多幅图。
            svg.append(("<path fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linejoin=\"round\" "
                    + "d=\"M%d %d H%d V%d H%d m%d -4 l%d 4 l%d 4\"><title>%s</title></path>")
                    .formatted(wall, start, track, end, wall, sign * 6, -sign * 6, sign * 6, escape(a.description())));
        }
        for (int i = 0; i < nodes.size(); i++) {
            svg.append(("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"8\" fill=\"#edf4f0\" stroke=\"#66867c\"/>"
                    + "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" fill=\"#243b35\">%s</text>")
                    .formatted(x, top[i], box, height[i], x + box / 2, top[i] + height[i] / 2 + 5, escape(nodes.get(i))));
        }
        for (var a : arrows) if (a.labeled()) {
            svg.append("<text x=\"8\" y=\"%d\" fill=\"currentColor\">%s</text>".formatted(y + 12, escape(a.description())));
            y += 20;
        }
        return svg.append("</svg>").toString();
    }

    private static int textWidth(String text) { return text.codePointCount(0, text.length()) * 15 + 24; }
    private static String escape(String text) { return HtmlUtils.htmlEscape(text, "UTF-8"); }
    private NativeArchitectureSvg() {}
}
