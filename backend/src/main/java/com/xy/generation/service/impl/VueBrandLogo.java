package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.web.util.HtmlUtils;

final class VueBrandLogo {
    static final String COMPONENT = "src/components/XyLogo.vue";
    static final ToolSpecification TOOL = ToolSpecification.builder().name("generateLogo")
            .description("写源码前为一处品牌标志提供名称、意象和配色。多个品牌可同批分别调用，相同设计在不同位置复用。")
            .parameters(JsonObjectSchema.builder().addStringProperty("name", "品牌名称，用作图片的无障碍名称")
                    .addStringProperty("purpose", "标志在页面中的用途或位置，仅用于页面编排")
                    .addStringProperty("brief", "可选的图形意象或风格，不含用户原始需求或其他个人信息")
                    .addProperty("layers", JsonArraySchema.builder().description("100×100 透明画布，从底到顶依次绘制")
                            .items(JsonObjectSchema.builder()
                                    .addEnumProperty("shape", List.of("polygon", "line", "circle"), "实心多边形、折线或实心圆")
                                    .addStringProperty("color", "六位十六进制色值，如 #265D72")
                                    .addProperty("coordinates", JsonArraySchema.builder()
                                            .description("0–100 整数。多边形沿轮廓给至少三对 x,y，折线至少两对；圆为 [圆心x,圆心y,半径]")
                                            .items(JsonIntegerSchema.builder().build()).build())
                                    .required("shape", "color", "coordinates").additionalProperties(false).build()).build())
                    .required("name", "layers").additionalProperties(false).build()).build();

    record DesignKey(String name, String brief, JsonNode layers) {}

    // 比较实际设计而非请求的写法；首次请求的源码仍保留原色值和绘制顺序。
    static DesignKey designKey(JsonNode design) {
        JsonNode layers = design.path("layers").deepCopy();
        if (layers.isArray()) {
            for (var layer : layers) if (layer instanceof ObjectNode shape) {
                shape.retain("shape", "color", "coordinates");
                if (shape.path("color").isTextual())
                    shape.put("color", shape.get("color").textValue().toLowerCase(Locale.ROOT));
            }
        }
        if (layers.isMissingNode() || (layers.isArray() && layers.isEmpty())) layers = null;
        return new DesignKey(design.path("name").isTextual() ? design.get("name").textValue().strip() : "",
                design.path("brief").asText("").strip(), layers);
    }

    static Optional<String> render(ObjectMapper mapper, String arguments) {
        return renderSvg(mapper, arguments).map(svg -> "<template>\n  " + svg + "\n</template>\n");
    }

    static Optional<String> renderSvg(ObjectMapper mapper, String arguments) {
        JsonNode design;
        try {
            design = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
        if (design == null || !design.path("name").isTextual() || design.get("name").textValue().isBlank())
            return Optional.empty();

        String name = HtmlUtils.htmlEscape(design.get("name").textValue().strip(), "UTF-8");
        return svgLayers(design.path("layers")).map(layers ->
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\""
                + " width=\"64\" height=\"64\" role=\"img\" aria-label=\"" + name + "\">\n" + layers + "  </svg>");
    }

    // 本地 SVG 与云端图像提示共用实际绘制的图层。未知字段不会进入任一成品。
    static Optional<String> svgLayers(JsonNode layers) {
        if (!layers.isArray() || layers.isEmpty()) return Optional.empty();
        var source = new StringBuilder();
        for (var layer : layers) {
            String element = renderLayer(layer);
            if (element == null) return Optional.empty();
            source.append("    ").append(element).append('\n');
        }
        return Optional.of(source.toString());
    }

    private static String renderLayer(JsonNode layer) {
        String shape = layer.path("shape").asText(""), color = layer.path("color").asText("");
        var coordinates = layer.path("coordinates");
        if (!color.matches("#[0-9a-fA-F]{6}") || !coordinates.isArray()) return null;
        int[] p = new int[coordinates.size()];
        for (int i = 0; i < p.length; i++) {
            var number = coordinates.get(i);
            if (!number.isIntegralNumber() || !number.canConvertToInt()) return null;
            p[i] = number.intValue();
            if (p[i] < 0 || p[i] > 100) return null;
        }
        if (shape.equals("circle")) return p.length == 3 && p[2] > 0
                ? "<circle cx=\"" + p[0] + "\" cy=\"" + p[1] + "\" r=\"" + p[2] + "\" fill=\"" + color + "\" />" : null;
        boolean filled = shape.equals("polygon");
        if ((!filled && !shape.equals("line")) || p.length % 2 != 0 || p.length < (filled ? 6 : 4)) return null;
        var points = new StringJoiner(" ");
        boolean hasLength = false;
        for (int i = 0; i < p.length; i += 2) {
            int x = p[i], y = p[i + 1];
            points.add(x + "," + y);
            hasLength |= x != p[0] || y != p[1];
        }
        if (filled) return hasFill(p) ? "<polygon points=\"" + points + "\" fill=\"" + color + "\" />" : null;
        return !hasLength ? null : "<polyline points=\"" + points + "\" fill=\"none\" stroke=\"" + color
                + "\" stroke-width=\"5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />";
    }

    // 与 SVG polygon 默认的 nonzero 填充一致：自交图形的两块可见区域不能相互抵消。
    private static boolean hasFill(int[] points) {
        var polygon = new Path2D.Double(Path2D.WIND_NON_ZERO, points.length / 2);
        polygon.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) polygon.lineTo(points[i], points[i + 1]);
        polygon.closePath();
        return !new Area(polygon).isEmpty();
    }

    private VueBrandLogo() {}
}
