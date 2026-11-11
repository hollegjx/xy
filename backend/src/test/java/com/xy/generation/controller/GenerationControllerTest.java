package com.xy.generation.controller;

import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.common.UserConstant;
import com.xy.generation.service.impl.GenerationPersistence;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.servlet.context-path=/api", "xy.ai.enabled=false",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class GenerationControllerTest {

    private static final String HTML = "<!DOCTYPE html><html><head><title>作品集</title></head><body>你好</body></html>";
    private static final AtomicLong IDS = new AtomicLong(9007199254740993L);
    @TempDir
    static Path directory;
    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    StreamingChatModel model;
    @MockitoBean
    GenerationPersistence persistence;
    @MockitoBean
    AppMapper appMapper;
    @MockitoBean
    UserMapper userMapper;
    private long generationId;

    @DynamicPropertySource
    static void outputDirectory(DynamicPropertyRegistry registry) {
        registry.add("xy.generation.output-directory", () -> directory.toString());
    }

    @BeforeEach
    void prepare() {
        User user = new User();
        user.setId(42L);
        user.setUserRole("admin");
        when(userMapper.selectOneById(42L)).thenReturn(user);
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "html"));
        generationId = IDS.incrementAndGet();
        when(persistence.start(55L, 42L, "摄影作品集")).thenReturn(generationId);
    }

    @Test
    void streamsAndReportsCompletionOnlyAfterSavingTheFileAndVersion() throws Exception {
        doAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            assertThat(((UserMessage) messages.get(1)).singleText()).isEqualTo("摄影作品集");
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(HTML);
            handler.onCompleteResponse(response(HTML, FinishReason.STOP));
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            assertThat(Files.readString(htmlPath())).isEqualTo(HTML);
            return null;
        }).when(persistence).complete(55L, 42L, generationId, HTML);

        String events = finish(mockMvc.perform(generate().session(session())).andExpect(request().asyncStarted()).andReturn());

        assertThat(events).containsSubsequence("event:started", "event:delta", "event:completed")
                .contains("\"generationId\":\"" + generationId + "\"").doesNotContain("event:failed");
        var order = inOrder(persistence);
        order.verify(persistence).recoverInterruptedGenerations();
        order.verify(persistence).start(55L, 42L, "摄影作品集");
        verify(persistence).complete(55L, 42L, generationId, HTML);
        verify(persistence, never()).fail(eq(generationId), any(), any());
        mockMvc.perform(cancel(generationId).session(session())).andExpect(status().isConflict());
        verify(persistence, never()).cancel(eq(generationId), any());
    }

    @Test
    void keepsPartialOutputOnModelFailureWithoutLeakingTheUpstreamError() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("<!DOCTYPE html>");
            handler.onError(new IllegalStateException("Authorization: Bearer test-secret"));
            // 已失败的旧回调不能再次完成，也不能更新成功版本。
            handler.onCompleteResponse(response(HTML, FinishReason.STOP));
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());

        assertThat(events).contains("event:failed").doesNotContain("test-secret", "event:completed");
        assertThat(htmlPath()).doesNotExist();
        verify(persistence).fail(generationId, "<!DOCTYPE html>", "模型调用失败，请检查模型配置或稍后重试");
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsTruncatedOrInvalidOutput(boolean truncated) throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            String text = truncated ? HTML : "这里是网站的说明，而不是 HTML";
            handler.onPartialResponse(text);
            handler.onCompleteResponse(response(text, truncated ? FinishReason.LENGTH : FinishReason.STOP));
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());

        assertThat(events).contains("event:failed").doesNotContain("event:completed");
        assertThat(htmlPath()).doesNotExist();
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
    }

    @Test
    void rejectsAnonymousAndOtherOwnersBeforeStartingAModelCall() throws Exception {
        mockMvc.perform(generate()).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40100));
        when(appMapper.selectOneById(55L)).thenReturn(app(84L, "html"));
        mockMvc.perform(generate().session(session())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        verifyNoInteractions(model, persistence);
    }

    @Test
    void rejectsUnsupportedModeAndInvalidPromptBeforeStarting() throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "multi_file"));
        mockMvc.perform(generate().session(session())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前生成接口仅支持单 HTML 模式"));
        mockMvc.perform(generate().session(session()).content("{\"prompt\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(model, persistence);
    }

    @Test
    void rejectsConcurrentGenerationAndAllowsAnExplicitRetryAfterFailure() throws Exception {
        AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            handler.set(invocation.getArgument(1));
            started.countDown();
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));
        MvcResult first = mockMvc.perform(generate().session(session())).andExpect(request().asyncStarted()).andReturn();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        try {
            mockMvc.perform(generate().session(session())).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(50001));
        } finally {
            handler.get().onError(new IllegalStateException("test failure"));
            finish(first);
        }
        doAnswer(invocation -> {
            ((StreamingChatResponseHandler) invocation.getArgument(1)).onError(new IllegalStateException("retry failure"));
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));
        assertThat(finish(mockMvc.perform(generate().session(session())).andReturn())).contains("event:started");
    }

    @Test
    void cancelsTheMatchingGenerationAndOldRequestsCannotStopItsReplacement() throws Exception {
        AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        StreamingHandle handle = mock(StreamingHandle.class);
        MvcResult first = startPendingGeneration(handler, handle);
        StreamingChatResponseHandler oldHandler = handler.get();
        mockMvc.perform(cancel(generationId + 100).session(session())).andExpect(status().isConflict());
        mockMvc.perform(cancel(generationId).session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        verify(persistence).cancel(generationId, "partial");
        verify(handle).cancel();
        assertThat(finish(first)).contains("event:cancelled", "\"status\":\"cancelled\"")
                .doesNotContain("event:completed", "event:failed");

        long nextId = generationId + 1;
        when(persistence.start(55L, 42L, "摄影作品集")).thenReturn(nextId);
        MvcResult next = startPendingGeneration(handler, mock(StreamingHandle.class));
        try {
            oldHandler.onPartialResponse("late output");
            oldHandler.onCompleteResponse(response(HTML, FinishReason.STOP));
            oldHandler.onError(new IllegalStateException("late failure"));
            mockMvc.perform(cancel(generationId).session(session()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(50001));
            mockMvc.perform(generate().session(session())).andExpect(status().isConflict());
            mockMvc.perform(cancel(nextId).session(session())).andExpect(status().isOk());
            verify(persistence, never()).complete(eq(55L), eq(42L), any(Long.class), any());
            assertThat(htmlPath()).doesNotExist();
        } finally {
            handler.get().onError(new IllegalStateException("test cleanup"));
            finish(next);
        }
    }

    @Test
    void cancellationPersistenceFailureIsReportedAndTheUserCanRetry() throws Exception {
        AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        StreamingHandle handle = mock(StreamingHandle.class);
        MvcResult result = startPendingGeneration(handler, handle);
        doThrow(new DataAccessResourceFailureException("test storage failure"))
                .when(persistence).cancel(generationId, "partial");
        try {
            mockMvc.perform(cancel(generationId).session(session()))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").isEmpty());
            verify(handle, never()).cancel();
            handler.get().onPartialResponse(" more");
            doNothing().when(persistence).cancel(generationId, "partial more");
            mockMvc.perform(cancel(generationId).session(session()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
            verify(persistence).cancel(generationId, "partial more");
            verify(handle).cancel();
        } finally {
            handler.get().onError(new IllegalStateException("test cleanup"));
            assertThat(finish(result)).contains("event:cancelled").doesNotContain("event:completed", "event:failed");
        }
    }

    @Test
    void rejectsCancellationWithoutOwnershipOrValidRunningGeneration() throws Exception {
        mockMvc.perform(cancel(generationId)).andExpect(status().isUnauthorized());
        when(appMapper.selectOneById(55L)).thenReturn(app(84L, "html"));
        mockMvc.perform(cancel(generationId).session(session())).andExpect(status().isForbidden());
        mockMvc.perform(cancel(0).session(session())).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/apps/0/generations/101/cancel").contextPath("/api").session(session()))
                .andExpect(status().isBadRequest());
        when(appMapper.selectOneById(55L)).thenReturn(null);
        mockMvc.perform(cancel(generationId).session(session())).andExpect(status().isNotFound());
        verifyNoInteractions(persistence, model);
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "html"));
        mockMvc.perform(cancel(generationId).session(session()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(50001));
        verify(persistence, never()).cancel(eq(generationId), any());
    }

    private MvcResult startPendingGeneration(AtomicReference<StreamingChatResponseHandler> handler,
            StreamingHandle handle) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            StreamingChatResponseHandler current = invocation.getArgument(1);
            handler.set(current);
            current.onPartialResponse(new PartialResponse("partial"), new PartialResponseContext(handle));
            started.countDown();
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));
        MvcResult result = mockMvc.perform(generate().session(session())).andExpect(request().asyncStarted()).andReturn();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        return result;
    }

    private MockHttpServletRequestBuilder cancel(long id) {
        return post("/api/apps/55/generations/{generationId}/cancel", id).contextPath("/api");
    }

    private String finish(MvcResult result) throws Exception {
        return mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder generate() {
        return post("/api/apps/55/generations").contextPath("/api")
                .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"摄影作品集\"}");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }

    private App app(long userId, String mode) {
        App app = new App();
        app.setId(55L);
        app.setUserId(userId);
        app.setCodeGenType(mode);
        return app;
    }

    private Path htmlPath() {
        return directory.resolve("42/55/" + generationId + "/index.html");
    }

    private ChatResponse response(String text, FinishReason reason) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(reason).build();
    }
}
