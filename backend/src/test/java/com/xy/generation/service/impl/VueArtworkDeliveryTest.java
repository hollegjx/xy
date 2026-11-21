package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.VueProjectBuildManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.FinishReason;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VueArtworkDeliveryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reRendersChangedRelationsAndReusesTheSuccessfulImageForPageOnlyEdits(@TempDir Path dir) throws Exception {
        var files = new GeneratedFileManager(dir);
        var artwork = mock(ArtworkProviders.class);
        when(artwork.rendersMermaid()).thenReturn(true);
        when(artwork.diagramImage(any())).thenAnswer(call -> "image:" + call.<VueArchitectureDiagram.Diagram>getArgument(0).nodes().getLast());
        var db = mock(GenerationPersistence.class);
        Map<String,String> template;
        try (var in = new ClassPathResource("templates/vue-project.json").getInputStream()) {
            template = json.readValue(in, new TypeReference<>() {});
        }
        String page = """
                <script setup>
                import System from './components/XyArchitecture.vue'
                import Order from './components/XyArchitecture2.vue'
                </script><template><System/><Order/></template>""";
        String orderData = "src/components/xy-architecture-2.json", orderImage = "src/components/xy-mermaid-image-2.json";
        for (long id : List.of(101L, 102L, 103L, 104L)) {
            boolean first = id == 101;
            if (!first) files.copyVueSources(1, 2, id - 1, id);
            String end = first ? "付款" : "发货";
            var system = Map.of("purpose", "系统概览", "nodes", List.of("客户端", "接口"), "edges", List.of(Map.of("from", "客户端", "to", "接口")));
            var order = Map.of("purpose", "下单流程", "nodes", List.of("下单", end), "edges", List.of(Map.of("from", "下单", "to", id == 104 ? "旧节点" : end)));
            var model = mock(StreamingChatModel.class);
            var round = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                int step = round.getAndIncrement();
                var handler = call.<StreamingChatResponseHandler>getArgument(1);
                String tool = null;
                Object args = null;
                if (first && step < 2) { tool = "drawArchitecture"; args = step == 0 ? system : order; }
                else if (step == (first ? 2 : 0)) {
                    tool = "writeFile";
                    args = first || id == 103 ? Map.of("relativePath", "src/App.vue", "content",
                            first ? page : page.replace("<System/>", "<h1>系统关系</h1><System/>"))
                            : Map.of("relativePath", orderData, "content", json.writeValueAsString(order));
                } else if (first && step == 3) {
                    tool = "readFile"; args = Map.of("relativePath", orderImage);
                } else if (first && step == 4) {
                    var result = (ToolExecutionResultMessage) call.<ChatRequest>getArgument(0).messages().getLast();
                    assertThat(result.text()).contains(orderData).doesNotContain("image:付款");
                }
                var reply = tool == null ? AiMessage.from("完成") : AiMessage.from(ToolExecutionRequest.builder()
                        .id("t" + step).name(tool).arguments(json.writeValueAsString(args)).build());
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(reply)
                        .finishReason(tool == null ? FinishReason.STOP : FinishReason.TOOL_EXECUTION).build());
                return null;
            }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            var output = new VueProjectGeneration(1, 2, id, files, json, template, mock(VueProjectBuildManager.class),
                    false, mock(OpenversePhotoSearch.class), Runnable::run, 10, artwork);
            var session = new GenerationSession(2, 1, id, new SseEmitter(), db, output, () -> {}, mock(ScheduledExecutorService.class));
            var instruction = SystemMessage.from("制作应用");
            List<ChatMessage> context = first ? List.of(instruction, UserMessage.from("画关系图"))
                    : List.of(instruction, AiMessage.from("已有工程"), UserMessage.from("修改节点和连线"));
            session.generate(model, context);
            if (id == 104) {
                verify(db).fail(eq(id), anyString(), contains("xy-architecture-2.json"));
                verify(db, never()).complete(eq(2L), eq(1L), eq(id), anyString());
                continue;
            }
            verify(db).complete(eq(2L), eq(1L), eq(id), anyString());
            var delivered = files.readVueProject(1,2,id);
            assertThat(json.readTree(delivered.get(ArtworkProviders.MERMAID_IMAGE)).get("src").asText()).isEqualTo("image:接口");
            assertThat(json.readTree(delivered.get(orderImage)).get("src").asText()).isEqualTo("image:" + end);
            assertThat(delivered.get("src/components/XyArchitecture2.vue")).contains("./xy-mermaid-image-2.json", "./xy-architecture-2.json");
        }
        assertThat(files.readVueProject(1,2,103).get(orderData)).contains("发货").doesNotContain("旧节点");
        verify(artwork, times(3)).diagramImage(any());
    }

    @Test
    void exhaustedDiagramKeepsItsOwnSlotAndDoesNotClaimOrdinaryJson(@TempDir Path dir) throws Exception {
        var files = new GeneratedFileManager(dir);
        var assets = new VueDiagramAssets(json, mock(ArtworkProviders.class), files, 1, 2, 3);
        var prompt = new VueAssetPrompt();
        String bad = "{\"nodes\":[\"入口\"],\"edges\":[{\"from\":\"入口\",\"to\":\"未知\"}]}";
        String good = "{\"nodes\":[\"下单\",\"付款\"],\"edges\":[{\"from\":\"下单\",\"to\":\"付款\"}]}";
        assertThat(assets.save(assets.prepare(bad, Runnable::run), prompt, n -> {})).contains("可修正一次");
        assertThat(assets.save(assets.prepare(bad, Runnable::run), prompt, n -> {})).contains("改用文字说明");
        assertThat(assets.save(assets.prepare(good, Runnable::run), prompt, n -> {})).contains("./components/XyArchitecture2.vue");
        String ordinary = "src/components/xy-architecture.json";
        files.writeVueSource(1,2,3, ordinary, "{\"title\":\"订单说明\"}");
        files.writeVueSource(1,2,3,"src/App.vue", """
                <script setup>import labels from './components/xy-architecture.json'; import Flow from './components/XyArchitecture2.vue'</script>
                <template><h1>{{labels.title}}</h1><Flow/></template>""");
        assertThat(files.vueArchitectureOrigins(1,2,3)).containsOnlyKeys(2);
        assertThat(files.readVueSource(1,2,3,"src/components/XyArchitecture2.vue")).contains("./xy-architecture-2.json");
        assertThat(assets.findError(files.readVueSources(1,2,3))).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = GeneratedFileManager.DiagramRenderer.class, names = {"IMAGE", "LEGACY"})
    void restoresARemovedRendererUsingTheCurrentRelations(GeneratedFileManager.DiagramRenderer origin, @TempDir Path dir) throws Exception {
        var files = new GeneratedFileManager(dir);
        var slot = new VueArchitectureDiagram.Slot(1);
        // 上一成功版只展示文字：关系已改为发货，停用的图片仍是付款。
        files.writeVueSource(1,2,3,"src/App.vue","<template>订单说明</template>");
        files.writeVueSource(1,2,3,slot.data(),"{\"nodes\":[\"下单\",\"发货\"],\"edges\":[{\"from\":\"下单\",\"to\":\"发货\"}]}");
        files.writeVueSource(1,2,3,slot.image(),"{\"src\":\"data:image/svg+xml;base64,PHN2Zy8+\"}");
        files.markVueArchitecture(1,2,3,1,origin);
        var artwork = mock(ArtworkProviders.class);
        when(artwork.diagramImage(any())).thenAnswer(call -> "image:" + call.<VueArchitectureDiagram.Diagram>getArgument(0).nodes().getLast());
        var assets = new VueDiagramAssets(json, artwork, files, 1, 2, 3);
        assets.inherit();
        files.writeVueSource(1,2,3,slot.component(),slot.renderer(true));
        files.writeVueSource(1,2,3,"src/App.vue","<script setup>import G from './components/XyArchitecture.vue'</script><template><G/></template>");
        var session = mock(GenerationSession.class);
        when(session.isRunning()).thenReturn(true);
        assertThat(assets.refreshImages(files.readVueSources(1,2,3), session)).isTrue();
        assertThat(files.readVueSource(1,2,3,slot.image())).contains("image:发货").doesNotContain("PHN2Zy8+");
    }

    @Test
    void localSvgDoesNotClaimAnOrdinaryJsonFileWithTheMermaidImageName(@TempDir Path dir) throws Exception {
        var files = new GeneratedFileManager(dir);
        var artwork = mock(ArtworkProviders.class);
        when(artwork.diagramImage(any())).thenReturn("错误覆盖");
        var slot = new VueArchitectureDiagram.Slot(1);
        var original = new VueDiagramAssets(json, artwork, files, 1, 2, 3);
        original.save(original.prepare("{\"nodes\":[\"甲\"],\"edges\":[{\"from\":\"甲\",\"to\":\"甲\"}]}",
                Runnable::run), new VueAssetPrompt(), text -> {});
        String banner = "{\"title\":\"作品介绍\",\"src\":\"banner.svg\"}";
        files.writeVueSource(1,2,3,slot.image(),banner);
        files.writeVueSource(1,2,3,"src/App.vue","""
                <script setup>import banner from './components/xy-mermaid-image.json'; import Graph from './components/XyArchitecture.vue'</script>
                <template><h1>{{banner.title}}</h1><Graph/></template>""");
        var session = mock(GenerationSession.class);
        when(session.isRunning()).thenReturn(true);
        original.refreshImages(files.readVueSources(1,2,3), session);
        assertThat(files.readVueSource(1,2,3,slot.image())).isEqualTo(banner);
        try (var in = new ClassPathResource("templates/vue-project.json").getInputStream()) {
            files.finishVueProject(1,2,3,json.readValue(in,new TypeReference<>() {}));
        }
        files.copyVueSources(1,2,3,4);
        var next = new VueDiagramAssets(json, artwork, files, 1, 2, 4);
        next.inherit();
        assertThat(next.imageDescription(slot.image())).isNull();
        next.refreshImages(files.readVueSources(1,2,4), session);
        assertThat(files.readVueSource(1,2,4,slot.image())).isEqualTo(banner);
        verify(artwork, never()).diagramImage(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyVersionKeepsTheUsersPngWhenThePageStartsShowingIt(boolean hasLocalRenderer, @TempDir Path dir) throws Exception {
        var files = new GeneratedFileManager(dir);
        var slot = new VueArchitectureDiagram.Slot(1);
        String banner = "{\"src\":\"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kRZkAAAAASUVORK5CYII=\"}";
        files.writeVueSource(1,2,3,slot.image(),banner);
        files.writeVueSource(1,2,3,slot.data(),"{\"nodes\":[\"甲\"],\"edges\":[{\"from\":\"甲\",\"to\":\"甲\"}]}");
        if (hasLocalRenderer) files.writeVueSource(1,2,3,slot.component(),slot.renderer(false));
        files.writeVueSource(1,2,3,"src/App.vue",hasLocalRenderer
                ? "<script setup>import G from './components/XyArchitecture.vue'</script><template><G/></template>"
                : "<template>旧版说明</template>");
        files.markVueArchitecture(1,2,3,1,GeneratedFileManager.DiagramRenderer.LEGACY);
        try (var in = new ClassPathResource("templates/vue-project.json").getInputStream()) {
            files.finishVueProject(1,2,3,json.readValue(in,new TypeReference<>() {}));
        }
        files.copyVueSources(1,2,3,4);
        var artwork = mock(ArtworkProviders.class);
        var next = new VueDiagramAssets(json, artwork, files, 1, 2, 4);
        next.inherit();
        assertThat(next.imageDescription(slot.image())).isNull();
        files.writeVueSource(1,2,4,"src/App.vue","""
                <script setup>import cover from './components/xy-mermaid-image.json'; import relations from './components/xy-architecture.json'</script>
                <template><h1>{{relations.nodes[0]}}</h1><img :src="cover.src" alt="作品封面"/></template>""");
        var session = mock(GenerationSession.class);
        when(session.isRunning()).thenReturn(true);
        assertThat(next.refreshImages(files.readVueSources(1,2,4), session)).isTrue();
        assertThat(files.readVueSource(1,2,4,slot.image())).isEqualTo(banner);
        assertThat(files.vueArchitectureOrigins(1,2,3).get(1)).isEqualTo(GeneratedFileManager.DiagramRenderer.LEGACY);
        verify(artwork, never()).diagramImage(any());
    }

    @Test
    void legacyImageRendererReusesTheImageAfterComponentRenameAndRestyling(@TempDir Path dir) throws Exception {
        var files = new GeneratedFileManager(dir);
        var slot = new VueArchitectureDiagram.Slot(1);
        String prior = "{\"src\":\"data:image/svg+xml;base64,PHN2Zy8+\"}";
        files.writeVueSource(1,2,3,slot.image(),prior);
        files.writeVueSource(1,2,3,slot.data(),"{\"nodes\":[\"甲\"],\"edges\":[{\"from\":\"甲\",\"to\":\"甲\"}]}");
        files.writeVueSource(1,2,3,"src/components/Flow.vue",slot.renderer(true)
                .replace("image from", "picture from").replace(":src=\"image.src\"", "v-bind:src=\"picture.src\"")
                .replace("diagram from", "flow from").replace("diagram.nodes", "flow.nodes").replace("100%", "90%"));
        files.writeVueSource(1,2,3,"src/App.vue","<script setup>import Flow from './components/Flow.vue'</script><template><Flow/></template>");
        files.markVueArchitecture(1,2,3,1,GeneratedFileManager.DiagramRenderer.LEGACY);
        var artwork = mock(ArtworkProviders.class);
        var assets = new VueDiagramAssets(json, artwork, files, 1, 2, 3);
        assets.inherit();
        assertThat(assets.imageDescription(slot.image())).contains(slot.data());
        var session = mock(GenerationSession.class);
        when(session.isRunning()).thenReturn(true);
        assertThat(assets.refreshImages(files.readVueSources(1,2,3),session)).isTrue();
        assertThat(files.readVueSource(1,2,3,slot.image())).isEqualTo(prior);
        verify(artwork, never()).diagramImage(any());
    }
}
