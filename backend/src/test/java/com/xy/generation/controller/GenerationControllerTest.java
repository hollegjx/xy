package com.xy.generation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.common.UserConstant;
import com.xy.generation.service.impl.GenerationPersistence;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.core.io.ClassPathResource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    GeneratedFileManager fileManager;
    @MockitoBean
    StreamingChatModel model;
    @MockitoBean
    GenerationPersistence persistence;
    @MockitoBean
    AppMapper appMapper;
    @MockitoBean
    UserMapper userMapper;
    @MockitoBean
    ChatHistoryMapper chatHistoryMapper;
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
    void multiFileGenerationSavesAllThreeFilesBeforeUpdatingTheVersion() throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "multi_file"));
        Map<String, String> files = multiFiles();
        String json = objectMapper.writeValueAsString(files);
        doAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            assertThat(((SystemMessage) messages.getFirst()).text()).contains("合法 JSON 对象", "style.css", "script.js");
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(json);
            handler.onCompleteResponse(response(json, FinishReason.STOP));
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            for (var file : files.entrySet()) {
                assertThat(Files.readString(htmlPath().resolveSibling(file.getKey()))).isEqualTo(file.getValue());
            }
            return null;
        }).when(persistence).complete(55L, 42L, generationId, json);

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).containsSubsequence("event:started", "event:delta", "event:completed").doesNotContain("event:failed");
        verify(persistence).complete(55L, 42L, generationId, json);
        verify(persistence, never()).fail(eq(generationId), any(), any());
    }

    @Test
    void vueToolsSaveSourceBeforeCompletionAndDeliverAZipWithoutEnablingPreviewOrDeploy() throws Exception {
        App app = app(42L, "vue_project");
        when(appMapper.selectOneById(55L)).thenReturn(app);
        String source = "<script setup>import Card from './components/Card.vue'</script><template><Card /></template>";
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (request.messages().getLast() instanceof ToolExecutionResultMessage) {
                assertThat(request.messages()).hasSize(5);
                handler.onCompleteResponse(response("已完成作品集。", FinishReason.STOP));
            } else {
                assertThat(request.toolSpecifications()).extracting("name").containsExactly("writeFile");
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(List.of(writeFile("src/App.vue", source),
                                writeFile("src/components/Card.vue", "<template>作品集</template>")))).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            assertThat(fileManager.readVueProject(42, 55, generationId)).containsEntry("src/App.vue", source);
            app.setGeneratedVersion(generationId);
            ChatHistory version = new ChatHistory();
            version.setAppId(55L);
            version.setUserId(42L);
            version.setMessageRole("assistant");
            version.setStatus("completed");
            when(chatHistoryMapper.selectOneById(generationId)).thenReturn(version);
            return null;
        }).when(persistence).complete(eq(55L), eq(42L), eq(generationId), any());

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).contains("已写入：src/components/Card.vue", "event:completed", "尚未构建")
                .doesNotContain("event:failed");
        Files.writeString(htmlPath().resolveSibling("application.yml"), "平台文件不能下载");
        byte[] archive = mockMvc.perform(get("/api/apps/55/source").contextPath("/api").session(session()))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, String> actual = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                actual.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(actual).containsOnlyKeys("package.json", "index.html", "vite.config.js", "src/main.js",
                "src/App.vue", "src/components/Card.vue").containsEntry("src/App.vue", source);
        mockMvc.perform(get("/api/apps/55/preview").contextPath("/api").session(session()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Vue 工程尚未接入构建与在线预览，请下载源码"));
        mockMvc.perform(post("/api/apps/55/deploy").contextPath("/api").session(session()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Vue 工程尚未接入构建与发布，请下载源码"));
        Files.delete(htmlPath().resolveSibling("src/App.vue"));
        mockMvc.perform(get("/api/apps/55/source").contextPath("/api").session(session()))
                .andExpect(status().isNotFound()).andExpect(content().contentType("application/json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing_entry", "invalid_path", "model_error", "cancelled"})
    void incompleteOrCancelledVueOutputDoesNotBecomeASuccessfulVersion(String outcome) throws Exception {
        App app = app(42L, "vue_project");
        long previousId = generationId - 1000;
        app.setGeneratedVersion(previousId);
        when(appMapper.selectOneById(55L)).thenReturn(app);
        when(persistence.loadSuccessfulPrompts(55, 42, previousId)).thenReturn(List.of("旧需求"));
        fileManager.writeVueSource(42, 55, previousId, "src/App.vue", "<template>旧作品</template>");
        Map<String, String> template = objectMapper.readValue(
                new ClassPathResource("templates/vue-project.json").getContentAsString(StandardCharsets.UTF_8),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        fileManager.finishVueProject(42, 55, previousId, template);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            assertThat(((AiMessage) request.messages().get(2)).text()).contains("旧作品");
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (outcome.equals("cancelled")) {
                StreamingHandle handle = mock(StreamingHandle.class);
                PartialToolCallContext context = mock(PartialToolCallContext.class);
                when(context.streamingHandle()).thenReturn(handle);
                handler.onPartialToolCall(null, context);
                mockMvc.perform(cancel(generationId).session(session())).andExpect(status().isOk());
                verify(handle).cancel();
            }
            if (outcome.equals("model_error")) handler.onError(new IllegalStateException("private model detail"));
            else if (outcome.equals("missing_entry")) handler.onCompleteResponse(response("完成", FinishReason.STOP));
            else handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(List.of(writeFile(
                                outcome.equals("invalid_path") ? "../App.vue" : "src/App.vue", "<template>页面</template>")))).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).contains(outcome.equals("cancelled") ? "event:cancelled" : "event:failed")
                .doesNotContain("event:completed", "private model detail");
        assertThat(htmlPath()).doesNotExist();
        assertThat(app.getGeneratedVersion()).isEqualTo(previousId);
        assertThat(fileManager.readVueProject(42, 55, previousId)).containsEntry("src/App.vue", "<template>旧作品</template>");
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
    }

    private ToolExecutionRequest writeFile(String path, String content) throws Exception {
        return ToolExecutionRequest.builder().id(path).name("writeFile")
                .arguments(objectMapper.writeValueAsString(Map.of("relativePath", path, "content", content))).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "multi_file"})
    void restoresTheLatestSavedSourceAndHistoryBeforeApplyingANewRequest(String mode) throws Exception {
        long versionId = generationId - 1000;
        App restored = app(42L, mode);
        restored.setGeneratedVersion(versionId);
        // 入口快照尚无成功版本；取得生成占用后必须重新读取数据库。
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, mode), restored);
        when(persistence.loadSuccessfulPrompts(55, 42, versionId)).thenReturn(List.of("建立作品集", "保留联系按钮"));
        String source = mode.equals("html") ? HTML : objectMapper.writeValueAsString(multiFiles());
        if (mode.equals("html")) fileManager.saveHtml(42, 55, versionId, source);
        else fileManager.saveMultiFile(42, 55, versionId, multiFiles());
        String prompt = "把背景改为黑色，保留其他内容";
        when(persistence.start(55, 42, prompt)).thenReturn(generationId);
        AtomicReference<List<ChatMessage>> sent = new AtomicReference<>();
        doAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            ((StreamingChatResponseHandler) invocation.getArgument(1)).onCompleteResponse(response(source, FinishReason.STOP));
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())
                .content(objectMapper.writeValueAsString(Map.of("prompt", prompt)))).andReturn());

        assertThat(events).contains("event:completed").doesNotContain("event:failed");
        assertThat(sent.get()).hasSize(4);
        assertThat(((UserMessage) sent.get().get(1)).singleText()).contains("建立作品集", "保留联系按钮")
                .doesNotContain(prompt);
        String baseline = ((AiMessage) sent.get().get(2)).text();
        if (mode.equals("html")) assertThat(baseline).isEqualTo(HTML);
        else assertThat(objectMapper.readTree(baseline)).isEqualTo(objectMapper.valueToTree(multiFiles()));
        assertThat(((UserMessage) sent.get().getLast()).singleText()).isEqualTo(prompt);
        verify(persistence).complete(55, 42, generationId, source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing_html", "missing_css", "history_error"})
    void contextReadFailureDoesNotCallTheModelOrReplaceTheSuccessfulVersion(String failure) throws Exception {
        long versionId = generationId - 1000;
        App restored = app(42L, failure.equals("missing_css") ? "multi_file" : "html");
        restored.setGeneratedVersion(versionId);
        when(appMapper.selectOneById(55L)).thenReturn(restored);
        when(persistence.loadSuccessfulPrompts(55, 42, versionId)).thenReturn(List.of("建立作品集"));
        if (failure.equals("missing_css")) {
            fileManager.saveMultiFile(42, 55, versionId, multiFiles());
            Files.delete(directory.resolve("42/55/" + versionId + "/style.css"));
        } else if (failure.equals("history_error")) {
            when(persistence.loadSuccessfulPrompts(55, 42, versionId))
                    .thenThrow(new DataAccessResourceFailureException("private storage detail"));
        }

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());

        assertThat(events).contains("event:failed", "读取历史或成功源码失败")
                .doesNotContain("event:completed", directory.toString(), "private storage detail");
        verifyNoInteractions(model);
        verify(persistence).fail(generationId, "", "读取历史或成功源码失败，请检查应用生成记录和文件后重试");
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
        assertThat(restored.getGeneratedVersion()).isEqualTo(versionId);
    }

    @Test
    void cancellationDuringContextLoadingPreventsTheModelCall() throws Exception {
        App restored = app(42L, "html");
        long versionId = generationId - 1000;
        restored.setGeneratedVersion(versionId);
        when(appMapper.selectOneById(55L)).thenReturn(restored);
        fileManager.saveHtml(42, 55, versionId, HTML);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(persistence.loadSuccessfulPrompts(55, 42, versionId)).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            reading.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return List.of("建立作品集");
        });
        MvcResult result = mockMvc.perform(generate().session(session())).andReturn();
        try {
            assertThat(reading.await(3, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(cancel(generationId).session(session())).andExpect(status().isOk());
        } finally {
            release.countDown();
        }
        assertThat(finish(result)).contains("event:cancelled").doesNotContain("event:completed", "event:failed");
        worker.get().join(3000);
        assertThat(worker.get().isAlive()).isFalse();
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "missing", "duplicate", "trailing", "wrong_type", "path"})
    void rejectsInvalidMultiFileOutputWithoutPublishingAVersion(String invalid) throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "multi_file"));
        Map<String, Object> files = new LinkedHashMap<>(multiFiles());
        if (invalid.equals("missing")) files.remove("script.js");
        if (invalid.equals("wrong_type")) files.put("style.css", 42);
        if (invalid.equals("path")) files.put("../style.css", files.remove("style.css"));
        String json = objectMapper.writeValueAsString(files);
        String output = switch (invalid) {
            case "malformed" -> json.substring(0, json.length() - 1);
            case "duplicate" -> json.substring(0, json.length() - 1) + ",\"style.css\":\"duplicate\"}";
            case "trailing" -> json + " {}";
            default -> json;
        };
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(output);
            handler.onCompleteResponse(response(output, FinishReason.STOP));
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).contains("event:failed", "模型未返回完整的 HTML/CSS/JS 文件").doesNotContain("event:completed");
        assertThat(htmlPath().getParent()).doesNotExist();
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
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
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "vue"));
        mockMvc.perform(generate().session(session())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前生成仅支持 html、multi_file 或 vue_project 模式"));
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

    @ParameterizedTest
    @ValueSource(strings = {"html", "multi_file"})
    void cancelsTheMatchingGenerationAndOldRequestsCannotStopItsReplacement(String mode) throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, mode));
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
            oldHandler.onCompleteResponse(response(mode.equals("html") ? HTML : objectMapper.writeValueAsString(multiFiles()),
                    FinishReason.STOP));
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

    private Map<String, String> multiFiles() {
        return Map.of("index.html", HTML.replace("</head>", "<link rel=\"stylesheet\" href=\"style.css\"></head>")
                        .replace("</body>", "<script src=\"script.js\"></script></body>"),
                "style.css", "body { color: #1d1d1f; }", "script.js", "document.body.dataset.loaded = 'true';");
    }

    private ChatResponse response(String text, FinishReason reason) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(reason).build();
    }
}
