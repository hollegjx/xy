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
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VueArtworkDeliveryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reRendersEditedMermaidDataBeforeCommittingEachVueVersion(@TempDir Path dir) throws Exception {
        var files = new GeneratedFileManager(dir);
        var artwork = mock(ArtworkProviders.class);
        when(artwork.rendersMermaid()).thenReturn(true);
        when(artwork.diagramImage(any())).thenAnswer(call -> "image:" + call.<VueArchitectureDiagram.Diagram>getArgument(0).nodes().getLast());
        var db = mock(GenerationPersistence.class);
        Map<String,String> template;
        try (var in = new ClassPathResource("templates/vue-project.json").getInputStream()) {
            template = json.readValue(in, new TypeReference<>() {});
        }
        for (long id : List.of(101L, 102L)) {
            boolean first = id == 101;
            if (!first) files.copyVueSources(1, 2, 101, id);
            String end = first ? "接口" : "网关";
            var design = Map.of("nodes", List.of("客户端", end), "edges", List.of(Map.of("from", "客户端", "to", end)));
            var model = mock(StreamingChatModel.class);
            var round = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                int step = round.getAndIncrement();
                var handler = call.<StreamingChatResponseHandler>getArgument(1);
                String tool = null;
                Object args = null;
                if (first && step == 0) { tool = "drawArchitecture"; args = design; }
                else if (step == (first ? 1 : 0)) {
                    tool = "writeFile";
                    args = first ? Map.of("relativePath", "src/App.vue", "content",
                            "<script setup>import Graph from './components/XyArchitecture.vue'</script><template><Graph/></template>")
                            : Map.of("relativePath", VueArchitectureDiagram.DATA, "content", json.writeValueAsString(design));
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
            var system = SystemMessage.from("制作应用");
            List<ChatMessage> context = first ? List.of(system, UserMessage.from("画关系图"))
                    : List.of(system, AiMessage.from("已有工程"), UserMessage.from("修改节点和连线"));
            session.generate(model, context);
            verify(db).complete(eq(2L), eq(1L), eq(id), anyString());
            assertThat(json.readTree(files.readVueProject(1,2,id).get(ArtworkProviders.MERMAID_IMAGE)).get("src").asText()).isEqualTo("image:" + end);
        }
        assertThat(files.readVueProject(1,2,101).get(ArtworkProviders.MERMAID_IMAGE)).contains("接口").doesNotContain("网关");
    }
}
