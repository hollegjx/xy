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
    void differentBrandsKeepTheirOwnCloudLogoAndAnIdenticalDesignIsGeneratedOnlyOnce() {
        var provider = mock(ArtworkProviders.class);
        when(provider.logoImage(any(), contains("书屋"))).thenReturn(Optional.of("书屋图"));
        when(provider.logoImage(any(), contains("乐队"))).thenReturn(Optional.of("乐队图"));
        var assets = new NativeAssetImages(provider);
        var json = new ObjectMapper();
        assertThat(assets.logo(json, "{\"name\":\"书屋\",\"brief\":\"书页\"}")).contains("书屋图");
        assertThat(assets.logo(json, "{\"name\":\"乐队\",\"brief\":\"音符\"}")).contains("乐队图");
        assertThat(assets.logo(json, "{ \"brief\":\"书页\", \"name\":\"书屋\" }")).contains("书屋图");
        verify(provider, times(2)).logoImage(any(), anyString());
    }

    @Test
    void submitsMermaidAndAnImageBriefAndKeepsDownloadsOffline() throws Exception {
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
            var logo = mock(ImageModel.class);
            when(logo.generate(contains("纸飞机"))).thenReturn(Response.from(Image.builder().base64Data(encoded).mimeType("image/png").build()));
            var provider = new ArtworkProviders(Optional.of(logo), "http://127.0.0.1:" + kroki.getAddress().getPort());
            var assets = new NativeAssetImages(provider);
            String diagram = assets.diagram(new VueArchitectureDiagram.Diagram(List.of("A", "B"), List.of(new VueArchitectureDiagram.Edge("A", "B", "请求"))));
            assertThat(sent.get()).contains("flowchart TD", "n0 -->|\"请求\"| n1");
            assertThat(diagram).contains("data:image/png;base64," + encoded);
            String design = "{\"name\":\"星云\",\"brief\":\"纸飞机\",\"layers\":[]}";
            String img = assets.logo(new ObjectMapper(), design).orElseThrow();
            assertThat(assets.logo(new ObjectMapper(), design)).contains(img);
            verify(logo).generate(contains("纸飞机"));
            var html = assets.conceal("<html><body>" + img + diagram + "</body></html>");
            assertThat(html).contains("星云", "架构图").doesNotContain(encoded);
            assertThat(assets.embed(html)).contains("data:image/png;base64," + encoded).doesNotContain("data-xy-art-id");
        } finally {
            kroki.stop(0);
        }
    }
}
