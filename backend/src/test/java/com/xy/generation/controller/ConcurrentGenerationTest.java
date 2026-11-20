package com.xy.generation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.common.UserConstant;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "xy.ai.enabled=true", "xy.ai.api-key=test-only", "xy.ai.model-name=test-page",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test"
})
@AutoConfigureMockMvc
class ConcurrentGenerationTest {

    private static final String PREFIX = "<!DOCTYPE html><html><head><title>作品</title></head><body>", END = "</body></html>";
    private static final CountDownLatch releaseFirst = new CountDownLatch(1);
    private static final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer gateway;
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired GeneratedFileManager files;
    @MockitoBean AppMapper apps;
    @MockitoBean UserMapper users;
    @MockitoBean ChatHistoryMapper messages;

    @DynamicPropertySource
    static void localModel(DynamicPropertyRegistry registry) throws IOException {
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.setExecutor(callers);
        gateway.createContext("/v1/chat/completions", ConcurrentGenerationTest::sendPage);
        gateway.start();
        registry.add("xy.ai.base-url", () -> "http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1");
        registry.add("xy.generation.output-directory", () -> directory.toString());
    }

    @AfterAll
    static void closeGateway() {
        releaseFirst.countDown();
        if (gateway != null) gateway.stop(0);
        callers.close();
    }

    @Test
    void anotherOwnerCompletesWhileTheFirstResponseIsPaused() throws Exception {
        var nextId = new AtomicLong(100);
        when(messages.insert(any(ChatHistory.class))).thenAnswer(invocation -> {
            ChatHistory message = invocation.getArgument(0);
            message.setId(nextId.incrementAndGet());
            return 1;
        });
        when(messages.finishGeneration(anyLong(), eq("completed"), anyString(), isNull())).thenReturn(1);
        when(apps.updateGeneratedVersion(anyLong(), anyLong(), anyLong())).thenReturn(1);
        var first = generate(11, 31, "alpha-site");
        try {
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThat(first.getResponse().getContentAsString(UTF_8)).contains("event:delta", "alpha-site"));
            var second = generate(22, 42, "beta-site");
            String events = finish(second);
            assertThat(events).doesNotContain("alpha-site");
            assertSavedPage(22, 42, "beta-site", events);
            verify(apps, never()).updateGeneratedVersion(eq(31L), eq(11L), anyLong());
        } finally {
            releaseFirst.countDown();
        }
        String events = finish(first);
        assertThat(events).doesNotContain("beta-site");
        assertSavedPage(11, 31, "alpha-site", events);
    }

    private void assertSavedPage(long owner, long appId, String prompt, String events) throws IOException {
        var version = ArgumentCaptor.forClass(Long.class);
        verify(apps).updateGeneratedVersion(eq(appId), eq(owner), version.capture());
        assertThat(events).containsSubsequence("event:started", "event:delta", prompt, "event:completed")
                .contains("\"generationId\":\"" + version.getValue() + "\"").doesNotContain("event:failed");
        String expected = PREFIX + prompt + END;
        verify(messages).finishGeneration(version.getValue(), "completed", expected, null);
        assertThat(files.readHtml(owner, appId, version.getValue())).isEqualTo(expected);
    }

    private MvcResult generate(long owner, long appId, String prompt) throws Exception {
        User user = new User();
        user.setId(owner);
        when(users.selectOneById(owner)).thenReturn(user);
        App app = new App();
        app.setId(appId);
        app.setUserId(owner);
        app.setCodeGenType("html");
        when(apps.selectOneById(appId)).thenReturn(app);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, owner);
        return mvc.perform(post("/apps/{id}/generations", appId).session(session)
                .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"" + prompt + "\"}")).andExpect(request().asyncStarted()).andReturn();
    }

    private String finish(MvcResult result) throws Exception {
        result.getAsyncResult(8_000);
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
    }

    private static void sendPage(HttpExchange exchange) throws IOException {
        try (exchange) {
            var chat = new ObjectMapper().readTree(exchange.getRequestBody()).path("messages");
            String prompt = chat.get(chat.size() - 1).path("content").asText();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var body = exchange.getResponseBody();
            chunk(body, PREFIX + prompt, "null");
            if ("alpha-site".equals(prompt)) {
                try {
                    if (!releaseFirst.await(25, TimeUnit.SECONDS)) return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            chunk(body, END, "\"stop\"");
            body.write("data: [DONE]\n\n".getBytes(UTF_8));
        }
    }

    private static void chunk(OutputStream body, String html, String finishReason) throws IOException {
        String event = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + html
                + "\"},\"finish_reason\":" + finishReason + "}]}\n\n";
        body.write(event.getBytes(UTF_8));
        body.flush();
    }
}
