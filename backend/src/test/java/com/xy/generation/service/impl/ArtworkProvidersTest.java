package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class ArtworkProvidersTest {
    @Test
    void submitsMermaidAndKeepsDownloadsOffline() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
        var sent = new AtomicReference<String>();
        var kroki = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kroki.createContext("/mermaid/png", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, bytes.size());
            try (var out = exchange.getResponseBody()) { bytes.writeTo(out); }
        });
        kroki.start();
        try {
            var provider = new ArtworkProviders("http://127.0.0.1:" + kroki.getAddress().getPort());
            var assets = new NativeAssetImages(provider);
            String diagram = assets.diagram(new VueArchitectureDiagram.Diagram(List.of("A", "B"), List.of(new VueArchitectureDiagram.Edge("A", "B", "请求"))));
            assertThat(sent.get()).contains("flowchart TD", "n0 -->|\"请求\"| n1");
            assertThat(diagram).contains("data:image/png;base64," + encoded);
            var html = assets.conceal("<html><body>" + diagram + "</body></html>");
            assertThat(html).contains("架构图").doesNotContain(encoded);
            assertThat(assets.embed(html)).contains("data:image/png;base64," + encoded).doesNotContain("data-xy-art-id");
        } finally {
            kroki.stop(0);
        }
    }
}
