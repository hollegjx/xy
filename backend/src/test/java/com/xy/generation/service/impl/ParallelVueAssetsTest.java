package com.xy.generation.service.impl;

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
import java.util.Optional;
import java.util.concurrent.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class ParallelVueAssetsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void providersStartTogetherAndOnlyAnActiveSessionCanUseTheirResults(boolean cancel, @TempDir Path dir) throws Exception {
        var files = new GeneratedFileManager(dir);
        var json = new ObjectMapper();
        var artwork = mock(ArtworkProviders.class);
        var photos = mock(OpenversePhotoSearch.class);
        var started = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(4);
        Runnable slowProvider = () -> {
            started.countDown();
            try {
                if (!release.await(15, SECONDS)) throw new AssertionError("素材未被释放");
            } catch (InterruptedException stopped) {
                interrupted.countDown(); // 模拟供应方在中断后仍返回一个结果
            }
        };
        when(artwork.rendersLogo()).thenReturn(true);
        when(artwork.rendersMermaid()).thenReturn(true);
        when(artwork.logoImage(any(), anyString())).thenAnswer(i -> {
            String design = i.getArgument(1);
            assertThat(design).doesNotContain("purpose", "导航", "赞助方", "页尾");
            slowProvider.run();
            return Optional.of("<img alt='" + json.readTree(design).get("name").asText() + "'/>");
        });
        when(artwork.diagramImage(any())).thenAnswer(i -> { slowProvider.run(); return "image:客户端到服务"; });
        when(photos.findMany("coffee", 1)).thenAnswer(i -> { slowProvider.run(); return List.of(); });
        var calls = List.of(
                tool("primary", "generateLogo", "{\"name\":\"星云\",\"purpose\":\"导航\"}"),
                tool("partner", "generateLogo", "{\"purpose\":\"赞助方\",\"name\":\"乐团\"}"),
                tool("footer", "generateLogo", "{\"purpose\":\"页尾\",\"name\":\"星云\"}"),
                tool("drawArchitecture", "{\"nodes\":[\"客户端\",\"服务\"],\"edges\":[{\"from\":\"客户端\",\"to\":\"服务\"}]}"),
                tool("findPhoto", "{\"theme\":\"coffee\"}"),
                tool("selectIllustration", "{\"theme\":\"reading\"}"));
        var model = mock(StreamingChatModel.class);
        var requests = new CopyOnWriteArrayList<ChatRequest>();
        doAnswer(i -> {
            requests.add(i.getArgument(0));
            var reply = switch (requests.size()) {
                case 1 -> AiMessage.from(calls);
                case 2 -> AiMessage.from(tool("writeFile", json.writeValueAsString(
                        Map.of("relativePath", "src/App.vue", "content", "<template>星云书店</template>"))));
                default -> AiMessage.from("完成");
            };
            i.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(ChatResponse.builder().aiMessage(reply)
                    .finishReason(reply.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var db = mock(GenerationPersistence.class);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = new VueProjectGeneration(1, 2, 3, files, json, Map.of(), mock(VueProjectBuildManager.class),
                    false, photos, workers, 10, artwork);
            var session = new GenerationSession(2, 1, 3, new SseEmitter(), db, output, () -> {}, mock(ScheduledExecutorService.class));
            var run = workers.submit(() -> session.generate(model, List.of(SystemMessage.from("制作网站"), UserMessage.from("做书店"))));
            try {
                assertThat(started.await(4, SECONDS)).as("图库、两份不同的 Logo、架构图都已开始").isTrue();
                await().atMost(4, SECONDS).until(() -> files.listVueSources(1, 2, 3).contains(ThemeIllustrations.VUE_COMPONENT));
                assertThat(requests).hasSize(1);
                if (cancel) {
                    session.cancel();
                    assertThat(interrupted.await(4, SECONDS)).as("取消应中断全部外部素材任务").isTrue();
                }
            } finally {
                release.countDown();
            }
            run.get(4, SECONDS);
        }
        if (cancel) {
            assertThat(requests).hasSize(1);
            assertThat(files.listVueSources(1, 2, 3)).containsExactly(ThemeIllustrations.VUE_COMPONENT);
            verify(db, never()).complete(anyLong(), anyLong(), anyLong(), anyString());
        } else {
            assertThat(requests).hasSize(3);
            assertThat(requests.get(1).messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast).map(ToolExecutionResultMessage::toolName))
                    .containsExactly("generateLogo", "generateLogo", "generateLogo", "drawArchitecture", "findPhoto", "selectIllustration");
            assertThat(files.readVueSource(1, 2, 3, ArtworkProviders.MERMAID_IMAGE)).contains("客户端到服务");
            assertThat(files.readVueSource(1, 2, 3, VueBrandLogo.COMPONENT)).contains("星云").doesNotContain("乐团");
            assertThat(files.readVueSource(1, 2, 3, "src/components/XyLogo2.vue")).contains("乐团").doesNotContain("星云");
            assertThat(((UserMessage) requests.get(1).messages().get(1)).singleText())
                    .containsOnlyOnce("XyLogo.vue").containsSubsequence("XyLogo.vue", "导航", "页尾", "XyLogo2.vue", "赞助方");
            verify(artwork, times(1)).diagramImage(any());
            verify(db).complete(eq(2L), eq(1L), eq(3L), anyString());
        }
        verify(artwork, times(2)).logoImage(any(), anyString());
    }

    private static ToolExecutionRequest tool(String name, String args) {
        return tool(name, name, args);
    }

    private static ToolExecutionRequest tool(String id, String name, String args) {
        return ToolExecutionRequest.builder().name(name).id(id).arguments(args).build();
    }
}
