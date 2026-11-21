package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.image.ImageModel;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
final class ArtworkProviders {
    static final String MERMAID_IMAGE = "src/components/xy-mermaid-image.json";
    static final String MERMAID_COMPONENT = """
            <script setup>
            import diagram from './xy-architecture.json'
            import image from './xy-mermaid-image.json'
            </script>
            <template><img :src="image.src" :alt="'架构图：' + diagram.nodes.join('、')" style="max-width:100%;height:auto" /></template>
            """;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private final Optional<ImageModel> logoModel;
    private final URI mermaid;

    ArtworkProviders(Optional<ImageModel> logoModel, @Value("${xy.artwork.kroki-url:}") String kroki) {
        this.logoModel = logoModel;
        this.mermaid = kroki.isBlank() ? null : URI.create(kroki.replaceAll("/+$", "") + "/mermaid/png");
    }
    boolean rendersMermaid() { return mermaid != null; }
    boolean rendersLogo() { return logoModel.isPresent(); }

    String diagramImage(VueArchitectureDiagram.Diagram diagram) {
        if (mermaid != null) {
            try {
                var request = HttpRequest.newBuilder(mermaid).timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(mermaidText(diagram))).build();
                return png(fetch(request));
            } catch (IOException | IllegalArgumentException unavailable) {
                // 已配置的自托管渲染器暂不可用，仍交付基于当前关系的本地绘图。
            }
        }
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(
                NativeArchitectureSvg.render(diagram).getBytes(StandardCharsets.UTF_8));
    }

    static String mermaidText(VueArchitectureDiagram.Diagram diagram) {
        var text = new StringBuilder("flowchart TD\n");
        var nodes = diagram.nodes();
        for (int i = 0; i < nodes.size(); i++) text.append("n").append(i).append("[\"").append(label(nodes.get(i))).append("\"]\n");
        for (var e : diagram.edges()) {
            text.append("n").append(nodes.indexOf(e.from())).append(" -->");
            if (!e.label().isEmpty()) text.append("|\"").append(label(e.label())).append("\"|");
            text.append(" n").append(nodes.indexOf(e.to())).append('\n');
        }
        return text.toString();
    }

    private static String label(String raw) {
        // 节点标识由后端生成，标点、换行及 Mermaid 指令均只能作为标签文本。
        var out = new StringBuilder();
        raw.codePoints().forEach(c -> { if (Character.isLetterOrDigit(c) || c == ' ') out.appendCodePoint(c);
            else out.append('#').append(c).append(';'); });
        return out.toString();
    }

    Optional<String> logoImage(ObjectMapper mapper, String arguments) {
        if (logoModel.isEmpty()) return Optional.empty();
        try {
            var design = mapper.readTree(arguments);
            String name = design.path("name").asText("").strip();
            if (name.isEmpty()) return Optional.empty();
            // 不发送用户原始需求、历史或工程源码，只提交 Logo 的设计参数。
            String brief = design.path("brief").asText("");
            var palette = new java.util.LinkedHashSet<String>();
            for (var layer : design.path("layers")) {
                String color = layer.path("color").asText("");
                if (color.matches("#[0-9A-Fa-f]{6}")) palette.add(color);
            }
            String prompt = "设计一个方形品牌标志。品牌：" + name + "。视觉简述：" + brief
                    + "。色板：" + String.join("、", palette);
            var image = logoModel.get().generate(prompt).content();
            byte[] bytes;
            if (image.base64Data() != null && image.base64Data().length() <= 4L * MAX_IMAGE_BYTES / 3 + 4) {
                bytes = Base64.getDecoder().decode(image.base64Data());
            } else {
                URI url = image.url();
                if (url == null || !"https".equals(url.getScheme()) || url.getUserInfo() != null
                        || url.getHost() == null || !url.getHost().matches("dashscope-result[-.][a-z0-9-]+\\.oss[-.][a-z0-9-]+\\.aliyuncs\\.com")) return Optional.empty();
                bytes = fetch(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(12)).GET().build());
            }
            return Optional.of("<img data-xy-artwork=\"logo\" alt=\"" + HtmlUtils.htmlEscape(name, "UTF-8")
                    + "\" width=\"64\" height=\"64\" src=\"" + png(bytes) + "\" />");
        } catch (IOException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static byte[] fetch(HttpRequest request) throws IOException {
        try {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) throw new IOException("素材服务不可用");
                return body.readNBytes(MAX_IMAGE_BYTES + 1);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("素材请求已停止");
        }
    }

    private static String png(byte[] bytes) throws IOException {
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (bytes.length <= 24 || bytes.length > MAX_IMAGE_BYTES || !Arrays.equals(bytes, 0, 8, signature, 0, 8))
            throw new IOException("素材服务未返回 PNG");
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
