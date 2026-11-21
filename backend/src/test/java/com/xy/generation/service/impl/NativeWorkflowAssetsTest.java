package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.manager.GeneratedFileManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NativeWorkflowAssetsTest {
    private static final String PLAN = """
            {"photos":[{"theme":"coffee"}],"illustrations":[{"theme":"reading"}],"logo":{"name":"书屋","brief":"书页","layers":[]},
             "architecture":{"nodes":["入口","书库"],"edges":[{"from":"入口","to":"书库"}]}}""";
    private static final String PAGE = """
            <!DOCTYPE html><html><head><title>书屋</title></head><body><img data-xy-photo="coffee" alt="咖啡">
            <!--xy:illustration:reading--><span data-xy-logo="prepared">书屋</span>
            <figure data-xy-architecture="prepared"><figcaption>读者与书库</figcaption></figure>
            <span id="other" data-xy-logo='{"name":"另一个标志"}'>其他品牌</span></body></html>""";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startsOnlyThePlannedProvidersTogetherAndReusesTheirResultsOnRepair(boolean cancel, @TempDir Path dir) throws Exception {
        var providers = mock(ArtworkProviders.class);
        var photos = mock(OpenversePhotoSearch.class);
        var started = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(3);
        Runnable waitForAll = () -> {
            started.countDown();
            try { if (!release.await(12, SECONDS)) throw new AssertionError("素材未收齐"); }
            catch (InterruptedException stopped) { interrupted.countDown(); }
        };
        when(photos.find("coffee")).thenAnswer(i -> {
            waitForAll.run();
            return Optional.of(new OpenversePhotoSearch.Photo("data:image/jpeg;base64,Y29mZmVl", "https://example.org/credit", "CC0"));
        });
        when(providers.logoImage(any(), anyString())).thenAnswer(i -> {
            assertThat(i.<String>getArgument(1)).contains("书屋", "书页").doesNotContain("私人备注");
            waitForAll.run();
            return Optional.of("<img data-xy-artwork='logo' alt='书屋' src='data:image/png;base64,bG9nbw=='>");
        });
        when(providers.rendersMermaid()).thenReturn(true);
        when(providers.diagramImage(any())).thenAnswer(i -> { waitForAll.run(); return "data:image/png;base64,ZGlhZ3JhbQ=="; });
        var model = mock(StreamingChatModel.class);
        var plans = new CopyOnWriteArrayList<String>();
        var reviews = new CopyOnWriteArrayList<String>();
        doAnswer(call -> {
            var messages = call.<ChatRequest>getArgument(0).messages();
            String input = ((UserMessage) messages.getLast()).singleText();
            String result;
            if (((SystemMessage) messages.getFirst()).text().contains("页面素材规划师")) {
                plans.add(input);
                result = PLAN;
            } else {
                reviews.add(input);
                result = reviews.size() == 1 ? "{\"isValid\":false,\"errors\":[\"增加标题\"],\"suggestions\":[]}"
                        : "{\"isValid\":true,\"errors\":[],\"suggestions\":[]}";
            }
            call.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(reply(result));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var writer = mock(NativeCodeStream.class);
        var drafts = new CopyOnWriteArrayList<List<ChatMessage>>();
        when(writer.generate(any(), any())).thenAnswer(call -> {
            drafts.add(call.getArgument(0));
            return Flux.just(new NativeCodeStream.Chunk(null, reply(drafts.size() == 1 ? PAGE : PAGE.replace("<body>", "<body><h1>书屋</h1>"))));
        });
        var files = new GeneratedFileManager(dir);
        var db = mock(GenerationPersistence.class);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = new NativeGenerationOutputHandler(1, 2, 3, "html", files, new ObjectMapper(), true,
                    photos, writer, providers, workers);
            var session = new GenerationSession(2, 1, 3, new SseEmitter(), db, output, () -> {}, mock(ScheduledExecutorService.class));
            var run = workers.submit(() -> session.generate(model, List.of(SystemMessage.from("设计网页"), UserMessage.from("书屋 {{私人备注}}"))));
            try {
                assertThat(started.await(4, SECONDS)).as("不用等图库返回，Logo 和架构图也要开始").isTrue();
                assertThat(drafts).isEmpty();
                if (cancel) {
                    session.cancel();
                    assertThat(interrupted.await(4, SECONDS)).as("停止应中断三个正在等待的调用").isTrue();
                }
            } finally { release.countDown(); }
            run.get(4, SECONDS);
        }
        assertThat(plans).singleElement().asString().contains("书屋 {{私人备注}}");
        verify(photos).find("coffee");
        verify(providers).logoImage(any(), anyString());
        verify(providers).diagramImage(any());
        if (cancel) {
            assertThat(drafts).isEmpty();
            assertThat(reviews).isEmpty();
            verify(db, never()).complete(anyLong(), anyLong(), anyLong(), anyString());
            try (var filesOnDisk = java.nio.file.Files.walk(dir)) { assertThat(filesOnDisk.filter(java.nio.file.Files::isRegularFile)).isEmpty(); }
        } else {
            assertThat(drafts).hasSize(2).allSatisfy(messages ->
                    assertThat(((UserMessage) messages.get(1)).singleText()).contains("书屋 {{私人备注}}", "data-xy-photo", "CC0",
                            "xy:illustration:reading", "data-xy-logo", "data-xy-architecture", "入口、书库").doesNotContain("base64,", "<svg"));
            assertThat(reviews).hasSize(2).allSatisfy(review -> assertThat(review).contains("CC0", "data-xy-art-id").doesNotContain("base64,"));
            var html = org.jsoup.Jsoup.parse(files.readHtml(1, 2, 3));
            assertThat(html.expectFirst("h1").text()).isEqualTo("书屋");
            assertThat(html.select("img").eachAttr("src")).containsExactly("data:image/jpeg;base64,Y29mZmVl",
                    "data:image/png;base64,bG9nbw==", "data:image/png;base64,ZGlhZ3JhbQ==");
            assertThat(html.select("svg")).hasSize(1);
            assertThat(html.expectFirst("#other").html()).isEqualTo("其他品牌");
            verify(db).complete(eq(2L), eq(1L), eq(3L), anyString());
        }
    }

    private static ChatResponse reply(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(FinishReason.STOP).build();
    }
}
