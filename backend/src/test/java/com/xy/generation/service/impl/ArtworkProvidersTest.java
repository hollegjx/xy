package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.Result;
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
    void aPlannedBrandBriefWithoutLayersUsesTheImageModelInBothGenerationPaths() throws Exception {
        var mapper = new ObjectMapper();
        var reply = Result.<String>builder().finishReason(FinishReason.STOP).content("""
                {"logos":[{"purpose":"内部展示区","design":{"name":"纸鸢","brief":"折纸风筝"}}]}
                """).build();
        var design = new WorkflowAssetPlanReader(mapper, reply).plan().logos().getFirst().design();
        String arguments = mapper.writeValueAsString(design);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var model = mock(ImageModel.class);
        when(model.generate(anyString())).thenReturn(Response.from(Image.builder()
                .base64Data(Base64.getEncoder().encodeToString(bytes.toByteArray())).build()));
        var cloud = new ArtworkProviders(Optional.of(model), "");

        assertThat(new NativeAssetImages(cloud).logo(mapper, arguments)).get().asString().contains("data:image/png");
        var vue = new VueLogoComponents().prepare(arguments, mapper, cloud, Runnable::run);
        assertThat(vue.drawing().source().join()).get().asString().contains("data:image/png");
        verify(model, times(2)).generate(argThat((String prompt) -> prompt.contains("折纸风筝") && !prompt.contains("内部展示区")));
        assertThat(new NativeAssetImages(new ArtworkProviders(Optional.empty(), "")).logo(mapper, arguments)).isEmpty();
    }

    @Test
    void malformedLogoCoordinatesDoNotReachTheProviderOrBlockTheNextDrawing() {
        var provider = mock(ArtworkProviders.class);
        when(provider.rendersLogo()).thenReturn(true);
        when(provider.logoImage(any(), anyString())).thenReturn(Optional.of("<img alt='星云'>"));
        var vue = new VueLogoComponents();
        var json = new ObjectMapper();
        String valid = """
                {"name":"星云","purpose":"私有页首","layers":[{"shape":"circle","color":"#112288","coordinates":[50,50,10]}]}""";
        for (var coords : List.of("[50,50,\"10\"]", "[50,50,10.5]")) {
            var rejected = vue.prepare(valid.replace("[50,50,10]", coords), json, provider, Runnable::run);
            assertThat(rejected.drawing().source().join()).isEmpty();
        }
        verifyNoInteractions(provider);
        var delivered = vue.prepare(valid, json, provider, Runnable::run);
        assertThat(delivered.purpose()).isEqualTo("私有页首");
        assertThat(delivered.drawing().source().join()).get().asString().contains("星云");
        verify(provider).logoImage(eq(json), argThat(args -> args.contains("[50,50,10]") && !args.contains("私有页首")));
    }

    @Test
    void equivalentLogoRequestsReuseTheSameDrawingAndVueComponent() {
        var mapper = new ObjectMapper();
        var provider = mock(ArtworkProviders.class);
        when(provider.rendersLogo()).thenReturn(true);
        when(provider.logoImage(any(), anyString())).thenReturn(Optional.of("<img alt='棱镜'>"));
        String initial = """
                {"name":"棱镜","purpose":"页头","layers":[{"shape":"circle","color":"#11aaBB","coordinates":[50,50,18]}]}""";
        String repeated = """
                {"name":" 棱镜 ","purpose":"页尾","brief":"","layers":[{"coordinates":[50,50,18],"color":"#11AAbb","shape":"circle","note":"内部"}]}""";
        String changed = initial.replace("[50,50,18]", "[30,50,18]");
        var vue = new VueLogoComponents();
        var first = vue.prepare(initial, mapper, provider, Runnable::run);
        var footer = vue.prepare(repeated, mapper, provider, Runnable::run);
        assertThat(footer.drawing()).isSameAs(first.drawing());
        assertThat(footer.purpose()).isEqualTo("页尾");
        assertThat(vue.prepare(changed, mapper, provider, Runnable::run).drawing().path()).endsWith("/XyLogo2.vue");
        verify(provider, times(2)).logoImage(any(), anyString());

        clearInvocations(provider);
        var nativeImages = new NativeAssetImages(provider);
        for (String design : List.of(initial, repeated, changed)) assertThat(nativeImages.logo(mapper, design)).isPresent();
        verify(provider, times(2)).logoImage(any(), anyString());
    }

    @Test
    void cloudLogoReceivesTheActualShapesInPaintOrderWithoutPageDetails() throws Exception {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        var model = mock(ImageModel.class);
        var prompts = new java.util.ArrayList<String>();
        when(model.generate(anyString())).thenAnswer(call -> {
            prompts.add(call.getArgument(0));
            return Response.from(Image.builder().base64Data(Base64.getEncoder().encodeToString(png.toByteArray())).build());
        });
        var provider = new ArtworkProviders(Optional.of(model), "");
        var json = new ObjectMapper();
        String circle = "{\"shape\":\"circle\",\"coordinates\":[50,50,18],\"color\":\"#112288\",\"note\":\"内部备注\"}";
        String triangle = "{\"shape\":\"polygon\",\"coordinates\":[5,95,50,5,95,95],\"color\":\"#112288\"}";
        String line = "{\"shape\":\"line\",\"coordinates\":[10,20,80,90],\"color\":\"#225533\"}";
        for (String layers : List.of(circle, triangle, triangle + "," + line, line + "," + triangle)) {
            String design = "{\"name\":\"棱镜\",\"purpose\":\"未公开的页头用途\",\"layers\":[" + layers + "]}";
            assertThat(provider.logoImage(json, design)).isPresent();
        }
        String invalid = "{\"name\":\"棱镜\",\"layers\":[" + circle.replace("[50,50,18]", "[50,50,0]") + "]}";
        assertThat(provider.logoImage(json, invalid)).isEmpty();
        assertThat(prompts).hasSize(4).allSatisfy(p -> assertThat(p).contains("棱镜", "#112288")
                .doesNotContain("未公开", "内部备注", "purpose", "note"));
        assertThat(prompts.get(0)).contains("<circle", "cx=\"50\"", "cy=\"50\"", "r=\"18\"").doesNotContain("<polygon");
        assertThat(prompts.get(1)).contains("<polygon", "5,95 50,5 95,95").doesNotContain("<circle");
        assertThat(prompts.get(2)).containsSubsequence("<polygon", "<polyline", "10,20 80,90", "#225533");
        assertThat(prompts.get(3)).containsSubsequence("<polyline", "<polygon");
    }

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
