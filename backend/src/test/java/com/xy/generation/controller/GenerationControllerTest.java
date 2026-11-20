package com.xy.generation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.common.UserConstant;
import com.xy.generation.service.impl.GenerationPersistence;
import com.xy.generation.service.impl.OpenversePhotoSearch;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.VueProjectBuildManager;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
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
    @MockitoBean
    OpenversePhotoSearch photoSearch;
    @MockitoBean
    VueProjectBuildManager builder;
    @MockitoBean(name = "generationHeartbeatScheduler")
    ScheduledExecutorService heartbeatScheduler;
    private final ScheduledFuture<?> heartbeatTask = mock(ScheduledFuture.class);
    private long generationId;

    @DynamicPropertySource
    static void outputDirectory(DynamicPropertyRegistry registry) {
        registry.add("xy.generation.output-directory", () -> directory.toString());
    }

    @BeforeEach
    void prepare() {
        doReturn(heartbeatTask).when(heartbeatScheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
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
                assertThat(request.toolSpecifications()).extracting("name")
                        .containsExactlyInAnyOrder("readFile", "writeFile", "replaceFragment", "deleteComponent",
                                "findPhoto", "selectIllustration", "drawArchitecture", "generateLogo");
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
        assertThat(unzipSources(archive)).containsOnlyKeys("package.json", "index.html", "vite.config.js", "src/main.js",
                "src/App.vue", "src/components/Card.vue").containsEntry("src/App.vue", source);
        mockMvc.perform(get("/api/apps/55/preview").contextPath("/api").session(session()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("该版本尚无构建产物，请启用 Vue 构建后重新生成；源码仍可下载"));
        mockMvc.perform(post("/api/apps/55/deploy").contextPath("/api").session(session()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("该版本尚无构建产物，请启用 Vue 构建后重新生成；源码仍可下载"));
        Files.delete(htmlPath().resolveSibling("src/App.vue"));
        mockMvc.perform(get("/api/apps/55/source").contextPath("/api").session(session()))
                .andExpect(status().isNotFound()).andExpect(content().contentType("application/json"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void codeModelReceivesTheOriginalRequestAndOnlySavedAssetsBeforeWriting(boolean qualityCheck) throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "vue_project"));
        String prompt = "做星云书店：咖啡区照片、阅读插画、系统架构和品牌 Logo。";
        when(persistence.start(55L, 42L, prompt)).thenReturn(generationId);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        String image = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        when(photoSearch.find("coffee")).thenReturn(java.util.Optional.of(new OpenversePhotoSearch.Photo(
                "data:image/jpeg;base64," + image, "https://www.flickr.com/photos/xy/123", "CC0")));
        String page = """
                <script setup>
                import photo from '../xy-photo.json'
                import Reading from './components/XyIllustration.vue'
                import Graph from './components/XyArchitecture.vue'
                import Logo from './components/XyLogo.vue'
                </script>
                <template><header><Logo/>星云书店</header><img :src="photo.src" alt="咖啡区"/><Reading/><Graph/></template>""";
        var prompts = new java.util.ArrayList<String>();
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (request.toolSpecifications() == null || request.toolSpecifications().isEmpty()) {
                handler.onCompleteResponse(response("{\"isValid\":true,\"errors\":[],\"suggestions\":[]}", FinishReason.STOP));
                return null;
            }
            prompts.add(((UserMessage) request.messages().get(1)).singleText());
            var calls = switch (prompts.size()) {
                case 1 -> List.of(assetTool("findPhoto", "{\"theme\":\"coffee\"}"),
                        assetTool("selectIllustration", "{\"theme\":\"reading\"}"));
                case 2 -> List.of(writeFile("src/Premature.vue", "<template>尚未看到素材</template>"),
                        assetTool("drawArchitecture", "{\"nodes\":[\"书店\",\"书目\"],\"edges\":[{\"from\":\"书店\",\"to\":\"书目\"}]}"),
                        assetTool("generateLogo", "{\"name\":\"星云\",\"layers\":[{\"shape\":\"circle\",\"color\":\"#24516B\",\"coordinates\":[50,50,35]}]}"));
                case 3 -> List.of(writeFile("src/App.vue", page));
                default -> List.<ToolExecutionRequest>of();
            };
            handler.onCompleteResponse(calls.isEmpty() ? response("完成", FinishReason.STOP)
                    : ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION).aiMessage(AiMessage.from(calls)).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())
                .content(objectMapper.writeValueAsString(Map.of("prompt", prompt, "qualityCheck", qualityCheck)))).andReturn());
        assertThat(events).containsSubsequence("2 项素材加入生成提示：内容照片、主题插画", "4 项素材加入生成提示：内容照片、主题插画、架构图、品牌 Logo",
                "已写入：src/App.vue", "event:completed").doesNotContain("已写入：src/Premature.vue", "event:failed", image);
        assertThat(prompts).hasSize(4);
        assertThat(prompts.getFirst()).isEqualTo(prompt);
        assertThat(prompts.get(1)).startsWith(prompt).contains("xy-photo.json", "CC0", "XyIllustration.vue", "学习阅读")
                .doesNotContain("XyArchitecture.vue", "XyLogo.vue", image);
        assertThat(prompts.get(2)).startsWith(prompt).containsOnlyOnce("本轮可用素材").containsOnlyOnce("XyIllustration.vue")
                .contains("xy-photo.json", "src/components/XyArchitecture.vue", "2 个节点、1 条连线", "src/components/XyLogo.vue")
                .doesNotContain(image);
        assertThat(prompts.getLast()).isEqualTo(prompts.get(2));
        var zip = unzipSources(fileManager.readVueSourceZip(42, 55, generationId));
        assertThat(zip).containsEntry("src/App.vue", page).doesNotContainKey("src/Premature.vue")
                .containsKeys("xy-photo.json", "src/components/XyIllustration.vue", "src/components/XyArchitecture.vue", "src/components/XyLogo.vue");
        verify(persistence).start(55L, 42L, prompt);
    }

    private static ToolExecutionRequest assetTool(String name, String json) {
        return ToolExecutionRequest.builder().name(name).id(name).arguments(json).build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void optionalPhotoReachesSourceZipBuildAndNextVersionOrFallsBack(boolean found) throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "vue_project"));
        var photo = new OpenversePhotoSearch.Photo("data:image/jpeg;base64,test", "https://www.flickr.com/photos/xy/123", "CC0");
        when(photoSearch.find("coffee")).thenReturn(found ? java.util.Optional.of(photo) : java.util.Optional.empty());
        String source = found ? "<script setup>import photo from '../xy-photo.json'</script><template><img :src='photo.src'></template>"
                : "<template>咖啡店</template>";
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (!(request.messages().getLast() instanceof ToolExecutionResultMessage result)) {
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder().name("findPhoto").id("photo")
                                .arguments("{\"theme\":\"coffee\"}").build())).build());
            } else if (result.toolName().equals("findPhoto")) {
                assertThat(result.text()).contains(found ? "xy-photo.json" : "无图布局").doesNotContain("base64");
                if (!found) assertThat(((UserMessage) request.messages().get(1)).singleText()).isEqualTo("摄影作品集");
                if (found) assertThat(result.text()).contains(photo.source(), photo.license());
                assertThat(request.toolSpecifications()).extracting("name").doesNotContain("findPhoto").contains("selectIllustration");
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(writeFile("src/App.vue", source))).build());
            } else handler.onCompleteResponse(response("完成", FinishReason.STOP));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).containsSubsequence("正在查找内容配图", found ? "已准备本地内容配图" : "未取得可用配图", "event:completed");
        var zip = unzipSources(fileManager.readVueSourceZip(42, 55, generationId));
        assertThat(zip).containsEntry("src/App.vue", source);
        if (found) {
            String json = objectMapper.writeValueAsString(photo);
            assertThat(zip).containsEntry("xy-photo.json", json);
            var inputs = fileManager.prepareVueBuild(42, 55, generationId);
            assertThat(Files.readString(inputs.source().resolve("xy-photo.json"))).isEqualTo(json);
            long next = IDS.incrementAndGet();
            assertThat(fileManager.copyVueSources(42, 55, generationId, next)).contains("xy-photo.json");
            assertThat(Files.readString(directory.resolve("42/55/" + next + "/xy-photo.json"))).isEqualTo(json);
        } else {
            assertThat(events).doesNotContain("素材加入生成提示");
            assertThat(zip).doesNotContainKey("xy-photo.json");
        }
    }

    @Test
    void creditsAnInheritedPhotoWithoutSendingTheEmbeddedImageToTheModel() throws Exception {
        String source = "https://www.flickr.com/photos/demo/123";
        var jpeg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "JPEG", jpeg);
        String image = java.util.Base64.getEncoder().encodeToString(jpeg.toByteArray());
        String photo = objectMapper.writeValueAsString(new OpenversePhotoSearch.Photo(
                "data:image/jpeg;base64," + image, source, "CC0"));
        String page = "<script setup>import photo from '../xy-photo.json'</script><template><img :src='photo.src'>";
        String original = page + "</template>";
        String credited = page + "<a :href='photo.source'>来源 · {{ photo.license }}</a></template>";
        long previous = preparePreviousVueProject(app(42L, "vue_project"), original);
        fileManager.saveVuePhoto(42, 55, previous, photo);
        var manifest = new AtomicReference<String>();
        var readResult = new AtomicReference<String>();
        var review = new AtomicReference<String>();
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (request.toolSpecifications() == null || request.toolSpecifications().isEmpty()) {
                review.set(((UserMessage) request.messages().getLast()).singleText());
                handler.onCompleteResponse(response("{\"isValid\":true,\"errors\":[],\"suggestions\":[]}", FinishReason.STOP));
            } else if (!(request.messages().getLast() instanceof ToolExecutionResultMessage result)) {
                manifest.set(((AiMessage) request.messages().get(2)).text());
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder().name("readFile").id("credit")
                                .arguments("{\"relativePath\":\"xy-photo.json\"}").build())).build());
            } else if ("readFile".equals(result.toolName())) {
                readResult.set(result.text());
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(writeFile("src/App.vue", credited))).build());
            } else handler.onCompleteResponse(response("已标注来源", FinishReason.STOP));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String prompt = "给已有照片标注来源和许可";
        when(persistence.start(55L, 42L, prompt)).thenReturn(generationId);
        String events = finish(mockMvc.perform(generate().session(session())
                .content(objectMapper.writeValueAsString(Map.of("prompt", prompt, "qualityCheck", true)))).andReturn());
        assertThat(events).contains("event:completed").doesNotContain("event:failed", "素材加入生成提示", image);
        for (String message : List.of(manifest.get(), readResult.get(), review.get())) {
            assertThat(message).contains(source, "CC0").doesNotContain(image);
        }
        var inherited = objectMapper.readTree(manifest.get());
        assertThat(inherited.path("readOnlyAssets").path("xy-photo.json")).isEqualTo(objectMapper.readTree(readResult.get()));
        assertThat(inherited.path("sourceFiles").toString()).contains("src/App.vue").doesNotContain("xy-photo.json");
        assertThat(unzipSources(fileManager.readVueSourceZip(42, 55, generationId)))
                .containsEntry("src/App.vue", credited).containsEntry("xy-photo.json", photo);
        assertThat(fileManager.readVueProject(42, 55, previous)).containsEntry("src/App.vue", original);
        verifyNoInteractions(photoSearch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reading", "teamwork", "analytics", "../outside"})
    void illustrationIsOptionalAndShippedInsideTheVueProject(String theme) throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "vue_project"));
        String label = Map.of("reading", "学习阅读", "teamwork", "团队协作", "analytics", "数据分析").get(theme);
        String component = "src/components/XyIllustration.vue";
        String page = label == null ? "<template>首页</template>" : """
                <script setup>import XyIllustration from './components/XyIllustration.vue'</script>
                <template><XyIllustration /></template>""";
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (!(request.messages().getLast() instanceof ToolExecutionResultMessage result)) {
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("select").name("selectIllustration")
                                .arguments(objectMapper.writeValueAsString(Map.of("theme", theme))).build())).build());
            } else if ("selectIllustration".equals(result.toolName())) {
                assertThat(result.text()).contains(label == null ? "不要引用" : "./components/XyIllustration.vue");
                assertThat(request.toolSpecifications()).extracting("name").doesNotContain("selectIllustration").contains("findPhoto");
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(writeFile("src/App.vue", page))).build());
            } else {
                assertThat(request.toolSpecifications()).extracting("name").doesNotContain("findPhoto", "selectIllustration", "drawArchitecture", "generateLogo");
                handler.onCompleteResponse(response("完成", FinishReason.STOP));
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).containsSubsequence(label == null ? "没有匹配的内置插画" : "已准备插画：" + label, "event:completed");
        var zip = unzipSources(fileManager.readVueSourceZip(42, 55, generationId));
        assertThat(zip).containsEntry("src/App.vue", page);
        if (label == null) assertThat(zip).doesNotContainKey(component);
        else {
            assertThat(zip.get(component)).contains("<svg", label);
            long nextId = IDS.incrementAndGet();
            fileManager.copyVueSources(42, 55, generationId, nextId);
            assertThat(fileManager.readVueSource(42, 55, nextId, component)).isEqualTo(zip.get(component));
        }
        verifyNoInteractions(photoSearch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"designed", "hourglass", "invalid", "skip", "dangling"})
    void brandLogoIsAnOptionalLocalComponentThatCanBeEditedNextTurn(String outcome) throws Exception {
        App app = app(42L, "vue_project");
        when(appMapper.selectOneById(55L)).thenReturn(app);
        String component = "src/components/XyLogo.vue", brand = "云帆书店";
        String prompt = switch (outcome) {
            case "skip" -> "只显示云帆书店文字，不要 Logo";
            case "hourglass" -> "为云帆书店画一个深蓝色沙漏 Logo";
            default -> "为云帆书店画深蓝色帆与橙色太阳的 Logo";
        };
        when(persistence.start(55L, 42L, prompt)).thenReturn(generationId);
        String design = """
                {"name":"云帆书店","layers":[{"shape":"circle","color":"#E7A340","coordinates":[75,24,11]},
                {"shape":"polygon","color":"#24516B","coordinates":[19,66,54,12,54,66]},
                {"shape":"line","color":"#24516B","coordinates":[14,74,28,84,67,84,84,70]}]}""";
        String withLogo = "<script setup>import XyLogo from './components/XyLogo.vue'</script>"
                + "<template><header><XyLogo/><span>云帆书店</span></header></template>";
        String textOnly = "<template><header>云帆书店</header></template>";
        boolean hourglass = outcome.equals("hourglass");
        boolean valid = outcome.equals("designed") || hourglass;
        String arguments = hourglass ? """
                {"name":"云帆书店","layers":[{"shape":"polygon","color":"#24516B","coordinates":[10,10,90,10,10,90,90,90]}]}"""
                : valid ? design : design.replace("19,66,54,12,54,66", "10,10,20,20,30,30");
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            StreamingChatResponseHandler handler = call.getArgument(1);
            ToolExecutionRequest tool;
            if (!(request.messages().getLast() instanceof ToolExecutionResultMessage result)) {
                tool = outcome.equals("skip") ? writeFile("src/App.vue", textOnly) :
                        ToolExecutionRequest.builder().name("generateLogo").id("logo")
                                .arguments(arguments).build();
            } else if ("generateLogo".equals(result.toolName())) {
                tool = writeFile("src/App.vue", result.text().contains("./components/XyLogo.vue") || outcome.equals("dangling")
                        ? withLogo : textOnly);
            } else {
                handler.onCompleteResponse(response("页面完成", FinishReason.STOP));
                return null;
            }
            handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                    .aiMessage(AiMessage.from(tool)).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String events = finish(mockMvc.perform(generate().session(session())
                .content(objectMapper.writeValueAsString(Map.of("prompt", prompt)))).andReturn());
        if (outcome.equals("dangling")) {
            assertThat(events).contains("event:failed", "仍引用不存在的 XyLogo.vue").doesNotContain("event:completed");
            verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
            return;
        }
        assertThat(events).contains("event:completed").doesNotContain("event:failed");
        var zip = unzipSources(fileManager.readVueSourceZip(42, 55, generationId));
        if (!valid) {
            assertThat(zip).containsEntry("src/App.vue", textOnly).doesNotContainKey(component);
            assertThat(events).doesNotContain("已生成品牌 Logo");
            if (outcome.equals("invalid")) assertThat(events).contains("改用品牌文字");
            return;
        }
        assertThat(events).contains("已生成品牌 Logo");
        assertThat(zip).containsEntry("src/App.vue", withLogo);
        String original = zip.get(component);
        var svg = org.jsoup.Jsoup.parse(original).selectFirst("svg");
        assertThat(svg.attr("aria-label")).isEqualTo(brand);
        if (hourglass) {
            assertThat(svg.selectFirst("polygon").attr("points")).isEqualTo("10,10 90,10 10,90 90,90");
            return;
        }
        assertThat(svg.selectFirst("polygon").attr("points")).isEqualTo("19,66 54,12 54,66");
        assertThat(svg.selectFirst("circle").attr("fill")).isEqualTo("#E7A340");
        assertThat(svg.selectFirst("polyline").attr("stroke")).isEqualTo("#24516B");

        app.setGeneratedVersion(generationId);
        long next = IDS.incrementAndGet();
        String change = "把太阳改为红色";
        when(persistence.start(55L, 42L, change)).thenReturn(next);
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (request.messages().getLast() instanceof ToolExecutionResultMessage) {
                handler.onCompleteResponse(response("已改色", FinishReason.STOP));
            } else {
                var edit = ToolExecutionRequest.builder().name("replaceFragment").id("recolor")
                        .arguments(objectMapper.writeValueAsString(Map.of("relativePath", component,
                                "oldText", "#E7A340", "newText", "#B14234"))).build();
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(edit)).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String updated = finish(mockMvc.perform(generate().session(session())
                .content(objectMapper.writeValueAsString(Map.of("prompt", change)))).andReturn());
        assertThat(updated).contains("已局部修改：" + component, "event:completed").doesNotContain("已生成品牌 Logo");
        assertThat(unzipSources(fileManager.readVueSourceZip(42, 55, next)))
                .containsEntry(component, original.replace("#E7A340", "#B14234")).containsEntry("src/App.vue", withLogo);
        assertThat(fileManager.readVueSource(42, 55, generationId, component)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void architectureToolDeliversDirectedGraphOrContinuesWithoutDanglingImport(int invalidAttempts) throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "vue_project"));
        String prompt = "绘制系统架构图";
        when(persistence.start(55L, 42L, prompt)).thenReturn(generationId);
        String graph = """
                {"nodes":["浏览器","后端","模型","数据库"],"edges":[{"from":"浏览器","to":"后端"},
                {"from":"后端","to":"模型","label":"调用"},{"from":"后端","to":"数据库","label":"读写"}]}""";
        String data = "src/components/xy-architecture.json", component = "src/components/XyArchitecture.vue";
        String page = invalidAttempts == 2 ? "<template>系统说明</template>" : """
                <script setup>import XyArchitecture from './components/XyArchitecture.vue'</script>
                <template><XyArchitecture /></template>""";
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            var tools = request.toolSpecifications().stream().map(t -> t.name()).toList();
            if (tools.contains("drawArchitecture")) {
                int n = calls.incrementAndGet();
                assertThat(n).isLessThanOrEqualTo(2);
                if (n == 2) assertThat(((ToolExecutionResultMessage) request.messages().getLast()).text()).contains("可修正一次");
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder().name("drawArchitecture").id("graph" + n)
                                .arguments(n <= invalidAttempts ? graph.replace("\"to\":\"模型\"", "\"to\":\"未知模块\"") : graph)
                                .build())).build());
            } else if (request.messages().getLast() instanceof ToolExecutionResultMessage result
                    && "drawArchitecture".equals(result.toolName())) {
                assertThat(result.text()).contains(invalidAttempts == 2 ? "文字说明" : "./components/XyArchitecture.vue");
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(writeFile("src/App.vue", page))).build());
            } else handler.onCompleteResponse(response("完成", FinishReason.STOP));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())
                .content(objectMapper.writeValueAsString(Map.of("prompt", prompt)))).andReturn());
        var zip = unzipSources(fileManager.readVueSourceZip(42, 55, generationId));
        assertThat(events).contains("event:completed");
        assertThat(zip).containsEntry("src/App.vue", page);
        if (invalidAttempts == 2) {
            assertThat(events).contains("改用文字说明").doesNotContain("已绘制架构图");
            assertThat(zip).doesNotContainKeys(data, component);
        } else {
            assertThat(events).contains("已绘制架构图：4 个节点、3 条连线");
            var actual = objectMapper.readTree(zip.get(data));
            assertThat(actual.path("nodes")).isEqualTo(objectMapper.readTree(graph).path("nodes"));
            assertThat(actual.path("edges").get(0).path("from").asText()).isEqualTo("浏览器");
            assertThat(actual.path("edges").get(0).path("to").asText()).isEqualTo("后端");
            assertThat(actual.path("edges").get(1)).isEqualTo(objectMapper.readTree(graph).path("edges").get(1));
            assertThat(actual.path("edges").get(2)).isEqualTo(objectMapper.readTree(graph).path("edges").get(2));
            assertThat(zip.get(component)).contains("./xy-architecture.json");
            long next = IDS.incrementAndGet();
            fileManager.copyVueSources(42, 55, generationId, next);
            assertThat(fileManager.readVueSource(42, 55, next, data)).isEqualTo(zip.get(data));
            assertThat(fileManager.readVueSource(42, 55, next, component)).isEqualTo(zip.get(component));
        }
        verifyNoInteractions(photoSearch);
    }

    private long preparePreviousVueProject(App app) throws Exception {
        return preparePreviousVueProject(app, "<template>旧作品</template>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dangling", "dangling-before-build", "mismatched-whitespace", "renamed-together",
            "renamed-component", "renamed-component-together"})
    void onlyPromotesDiagramEditsWhoseFinalEdgesStillConnect(String edit) throws Exception {
        String data = "src/components/xy-architecture.json", component = "src/components/XyArchitecture.vue";
        String graph = "{\"nodes\":[\"浏览器\",\"接口\"],\"edges\":[{\"from\":\"浏览器\",\"to\":\"接口\"}]}";
        App app = app(42L, "vue_project");
        String page = "<script setup>import Graph from './components/XyArchitecture.vue'</script><template><Graph/></template>";
        String renderer = new ClassPathResource("diagrams/XyArchitecture.vue").getContentAsString(StandardCharsets.UTF_8);
        long previous = preparePreviousVueProject(app, page);
        fileManager.writeVueSource(42, 55, previous, component, renderer);
        fileManager.writeVueSource(42, 55, previous, data, graph);
        boolean both = edit.endsWith("-together"), renameComponent = edit.startsWith("renamed-component");
        when(builder.enabled()).thenReturn(edit.equals("dangling-before-build"));
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (!(request.messages().getLast() instanceof ToolExecutionResultMessage result)) {
                String changed = graph.replace("接口\"", "网关\"");
                if (edit.equals("mismatched-whitespace")) changed = changed.replace("网关\"]", " 网关 \"]");
                else changed = changed.replace("\"to\":\"网关\"", "\"to\":\"接口\"");
                var edits = renameComponent ? List.of(
                        writeFile("src/components/SystemDiagram.vue", renderer),
                        writeFile("src/App.vue", page.replace("XyArchitecture", "SystemDiagram")),
                        ToolExecutionRequest.builder().id("remove-old").name("deleteComponent")
                                .arguments(objectMapper.writeValueAsString(Map.of("relativePath", component))).build(),
                        writeFile(data, changed)) : List.of(writeFile(data, changed));
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(edits)).build());
            } else if (both && result.toolName().equals("writeFile")) {
                var fixEdge = ToolExecutionRequest.builder().name("replaceFragment").id("rename-edge")
                        .arguments(objectMapper.writeValueAsString(Map.of("relativePath", data,
                                "oldText", "\"to\":\"接口\"", "newText", "\"to\":\"网关\""))).build();
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(fixEdge)).build());
            } else handler.onCompleteResponse(response("已改名", FinishReason.STOP));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        if (both) {
            assertThat(events).contains("event:completed").doesNotContain("event:failed");
            var zip = unzipSources(fileManager.readVueSourceZip(42, 55, generationId));
            assertThat(zip).containsEntry(data, graph.replace("接口", "网关"));
            if (renameComponent) assertThat(zip).containsEntry("src/components/SystemDiagram.vue", renderer)
                    .doesNotContainKey(component);
        } else {
            assertThat(events).contains("event:failed", "xy-architecture.json", "连线").doesNotContain("event:completed");
            verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
            verify(builder, never()).build(any(), any());
            assertThat(unzipSources(fileManager.readVueSourceZip(42, 55, app.getGeneratedVersion())))
                    .containsEntry(data, graph).containsEntry(component, renderer).containsEntry("src/App.vue", page);
        }
    }

    @Test
    void sameNamedComponentWithoutDiagramDataCanComplete() throws Exception {
        App app = app(42L, "vue_project");
        when(appMapper.selectOneById(55L)).thenReturn(app);
        String component = "src/components/XyArchitecture.vue";
        String ordinary = """
                <script setup>
                // 下面只是注释：import graph from './xy-architecture.json'
                import labels from './labels/xy-architecture.json'
                </script>
                <template><p>{{ labels.caption }}</p></template>""";
        String page = "<script setup>import Caption from './components/XyArchitecture.vue'</script><template><Caption/></template>";
        doAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            StreamingChatResponseHandler handler = call.getArgument(1);
            if (request.messages().getLast() instanceof ToolExecutionResultMessage) {
                handler.onCompleteResponse(response("完成", FinishReason.STOP));
            } else {
                var edits = List.of(writeFile(component, ordinary), writeFile("src/App.vue", page),
                        writeFile("src/components/labels/xy-architecture.json", "{\"caption\":\"Browser → API\"}"));
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(edits)).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).contains("event:completed").doesNotContain("event:failed");
        assertThat(unzipSources(fileManager.readVueSourceZip(42, 55, generationId)))
                .containsEntry(component, ordinary).containsEntry("src/App.vue", page).doesNotContainKey("src/components/xy-architecture.json");
    }

    private long preparePreviousVueProject(App app, String appSource) throws Exception {
        long previousId = generationId - 1000;
        app.setGeneratedVersion(previousId);
        when(appMapper.selectOneById(55L)).thenReturn(app);
        when(persistence.loadSuccessfulPrompts(55, 42, previousId)).thenReturn(List.of("旧需求"));
        fileManager.writeVueSource(42, 55, previousId, "src/App.vue", appSource);
        fileManager.writeVueSource(42, 55, previousId, "src/Card.vue", "<template>旧卡片</template>");
        Map<String, String> template = objectMapper.readValue(
                new ClassPathResource("templates/vue-project.json").getContentAsString(StandardCharsets.UTF_8),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        fileManager.finishVueProject(42, 55, previousId, template);
        return previousId;
    }

    @Test
    void editsOneVueComponentFromThePersistedVersionWithoutRewritingOtherFiles() throws Exception {
        long previousId = preparePreviousVueProject(app(42L, "vue_project"));
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (request.messages().getLast() instanceof ToolExecutionResultMessage result) {
                if (result.toolName().equals("readFile")) {
                    assertThat(result.text()).isEqualTo("<template>旧卡片</template>");
                    handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                            .aiMessage(AiMessage.from(replaceCardFragment("旧卡片", "新卡片"))).build());
                } else handler.onCompleteResponse(response("已更新卡片", FinishReason.STOP));
            } else {
                assertThat(request.toolSpecifications()).extracting("name").doesNotContain("findPhoto", "selectIllustration", "drawArchitecture", "generateLogo");
                assertThat(((AiMessage) request.messages().get(2)).text())
                        .contains("src/App.vue", "src/Card.vue").doesNotContain("旧卡片", "旧作品");
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder().name("readFile").id("read-card")
                                .arguments("{\"relativePath\":\"src/Card.vue\"}").build())).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).containsSubsequence("已读取：src/Card.vue", "已局部修改：src/Card.vue", "event:completed")
                .doesNotContain("旧卡片", "已写入：src/App.vue");
        assertThat(fileManager.readVueProject(42, 55, generationId))
                .containsEntry("src/App.vue", "<template>旧作品</template>").containsEntry("src/Card.vue", "<template>新卡片</template>");
        assertThat(fileManager.readVueProject(42, 55, previousId)).containsEntry("src/Card.vue", "<template>旧卡片</template>");
        verify(persistence).complete(eq(55L), eq(42L), eq(generationId), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"不存在", "template"})
    void unmatchedOrAmbiguousFragmentKeepsBothVersionsUnchanged(String oldText) throws Exception {
        App app = app(42L, "vue_project");
        long previous = preparePreviousVueProject(app);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                    .aiMessage(AiMessage.from(replaceCardFragment(oldText, "替换后的内容"))).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).contains("event:failed", "原代码片段未找到或匹配多处").doesNotContain("event:completed");
        for (long id : List.of(previous, generationId)) {
            assertThat(fileManager.readVueSource(42, 55, id, "src/Card.vue")).isEqualTo("<template>旧卡片</template>");
        }
        assertThat(app.getGeneratedVersion()).isEqualTo(previous);
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
    }

    private ToolExecutionRequest replaceCardFragment(String oldText, String newText) throws Exception {
        return ToolExecutionRequest.builder().id("edit-card").name("replaceFragment")
                .arguments(objectMapper.writeValueAsString(Map.of("relativePath", "src/Card.vue",
                        "oldText", oldText, "newText", newText))).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script setup>import Card from './Card.vue'</script><template><Card/></template>",
            "<template><button @click=\"import('./Card.vue')\">打开</button></template>",
            "<template><form><textarea /><component :is=\"() => import('./Card.vue')\" /></form></template>",
            "<script setup>const hasApostrophe = /'/;\nimport Card from './Card.vue'</script><template><Card/></template>"
    })
    void deletingAStillImportedComponentFailsWithoutBuildingOrReplacingTheDownload(String source) throws Exception {
        App app = app(42L, "vue_project");
        long previous = preparePreviousVueProject(app, source);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (request.messages().getLast() instanceof ToolExecutionResultMessage) {
                handler.onCompleteResponse(response("完成", FinishReason.STOP));
            } else {
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("remove").name("deleteComponent")
                                .arguments("{\"relativePath\":\"src/Card.vue\"}").build())).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).contains("event:failed", "src/App.vue", "仍引用已删除组件 src/Card.vue")
                .doesNotContain("event:completed");
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
        assertThat(app.getGeneratedVersion()).isEqualTo(previous);
        ChatHistory saved = new ChatHistory();
        saved.setAppId(55L);
        saved.setUserId(42L);
        saved.setMessageRole("assistant");
        saved.setStatus("completed");
        when(chatHistoryMapper.selectOneById(previous)).thenReturn(saved);
        byte[] download = mockMvc.perform(get("/api/apps/55/source").contextPath("/api").session(session()))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(unzipSources(download))
                .containsEntry("src/App.vue", source).containsEntry("src/Card.vue", "<template>旧卡片</template>");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void removesComponentOnlyFromNewVueVersion(boolean referenced) throws Exception {
        String updated = "<template><main>首页</main></template>";
        String original = referenced ? "<script setup>import Card from './Card.vue'</script><template><Card/></template>" : updated;
        long previousId = preparePreviousVueProject(app(42L, "vue_project"), original);
        var deletion = ToolExecutionRequest.builder().id("remove-card").name("deleteComponent")
                .arguments("{\"relativePath\":\"src/Card.vue\"}").build();
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (request.messages().getLast() instanceof ToolExecutionResultMessage) {
                handler.onCompleteResponse(response("完成", FinishReason.STOP));
            } else {
                var tools = referenced ? List.of(deletion, writeFile("src/App.vue", updated)) : List.of(deletion);
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(tools)).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).containsSubsequence("已删除组件：src/Card.vue", "event:completed");
        assertThat(unzipSources(fileManager.readVueSourceZip(42, 55, generationId)))
                .containsEntry("src/App.vue", updated).doesNotContainKey("src/Card.vue");
        assertThat(fileManager.readVueProject(42, 55, previousId))
                .containsEntry("src/App.vue", original).containsKey("src/Card.vue");
        verify(persistence).complete(eq(55L), eq(42L), eq(generationId), any());
    }

    private static Map<String, String> unzipSources(byte[] archive) throws IOException {
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @ParameterizedTest
    @ValueSource(strings = {"no_writes", "invalid_path", "invalid_read", "model_error", "cancelled"})
    void incompleteOrCancelledVueOutputDoesNotBecomeASuccessfulVersion(String outcome) throws Exception {
        App app = app(42L, "vue_project");
        long previousId = preparePreviousVueProject(app);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            assertThat(((AiMessage) request.messages().get(2)).text()).contains("src/App.vue");
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
            else if (outcome.equals("no_writes")) handler.onCompleteResponse(response("完成", FinishReason.STOP));
            else if (outcome.equals("invalid_read")) handler.onCompleteResponse(ChatResponse.builder()
                    .finishReason(FinishReason.TOOL_EXECUTION).aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                            .name("readFile").id("read").arguments("{\"relativePath\":\"src/../preview.html\"}").build())).build());
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
    @ValueSource(strings = {"passed", "repaired", "failed", "invalid_report"})
    void qualityWorkflowChecksCurrentSourcesAndRepairsAtMostOnceBeforeBuilding(String outcome) throws Exception {
        App app = app(42L, "vue_project");
        long previous = preparePreviousVueProject(app);
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        List<String> suggestions = outcome.equals("failed")
                ? Collections.nCopies(5, "请检查表单按钮的点击事件及其绑定。".repeat(5)) : List.of();
        if (outcome.equals("failed")) {
            // 使用实际的失败补写队列，只在 Mapper 边界模拟 VARCHAR(256)。
            var storage = delegatesTo(new GenerationPersistence(chatHistoryMapper, appMapper));
            doAnswer(storage).when(persistence).fail(eq(generationId), any(), any());
            doAnswer(storage).when(persistence).recoverInterruptedGenerations();
            when(chatHistoryMapper.finishGeneration(eq(generationId), eq("failed"), any(), any())).thenAnswer(call -> {
                String reason = call.getArgument(3);
                if (reason.codePointCount(0, reason.length()) > 256) {
                    throw new DataIntegrityViolationException("errorMessage 超过 256 字符");
                }
                return 1;
            });
        }
        when(builder.enabled()).thenReturn(true);
        doAnswer(invocation -> {
            var paths = (GeneratedFileManager.VueBuildPaths) invocation.getArgument(0);
            Files.writeString(paths.output().resolve("index.html"), HTML.replace("</head>",
                    "<script type=\"module\" src=\"./app.js\"></script></head>"));
            Files.writeString(paths.output().resolve("app.js"), "document.body.dataset.vue = 'ready'");
            return null;
        }).when(builder).build(any(), any());
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (request.toolSpecifications() == null || request.toolSpecifications().isEmpty()) {
                int check = checks.incrementAndGet();
                assertThat(request.messages().toString()).contains("摄影作品集", "src/App.vue", "src/Card.vue", "候选" + writes.get());
                boolean valid = outcome.equals("passed") || outcome.equals("repaired") && check == 2;
                String report = outcome.equals("invalid_report") ? "检查好了"
                        : objectMapper.writeValueAsString(Map.of("isValid", valid,
                                "errors", valid ? List.of() : List.of("按钮未响应"), "suggestions", suggestions));
                handler.onPartialResponse(report);
                handler.onCompleteResponse(response(report, FinishReason.STOP));
            } else if (request.messages().getLast() instanceof ToolExecutionResultMessage) {
                handler.onCompleteResponse(response("源码完成", FinishReason.STOP));
            } else {
                if (writes.get() > 0) assertThat(request.messages().toString()).contains("按钮未响应");
                handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(writeFile("src/App.vue",
                                "<template>候选" + writes.incrementAndGet() + "</template>"))).build());
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        String events = finish(mockMvc.perform(generate().session(session())
                .content("{\"prompt\":\"摄影作品集\",\"qualityCheck\":true}")).andReturn());
        boolean repaired = outcome.equals("repaired") || outcome.equals("failed");
        assertThat(checks.get()).isEqualTo(repaired ? 2 : 1);
        assertThat(writes.get()).isEqualTo(repaired ? 2 : 1);
        assertThat(events).contains("正在检查 Vue 工程").doesNotContain("isValid", "suggestions", "检查好了");
        if (repaired) assertThat(events).contains("正在修复（最多一次）");
        if (outcome.equals("passed") || outcome.equals("repaired")) {
            assertThat(events).containsSubsequence("检查通过", "正在构建 Vue 工程", "event:completed")
                    .doesNotContain("event:failed");
            verify(builder).build(any(), any());
            verify(persistence).complete(eq(55L), eq(42L), eq(generationId), any());
        } else {
            assertThat(events).contains("event:failed").doesNotContain("event:completed", "正在构建 Vue 工程");
            verify(builder, never()).build(any(), any());
            verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
            assertThat(app.getGeneratedVersion()).isEqualTo(previous);
        }
        assertThat(fileManager.readVueProject(42, 55, previous)).containsEntry("src/App.vue", "<template>旧作品</template>");
        if (outcome.equals("failed")) {
            assertOtherAppCanQueryAndGenerate();
            String reason = "自动修复一次后，Vue 工程仍未通过 AI 质检，请重试";
            verify(chatHistoryMapper).finishGeneration(eq(generationId), eq("failed"), any(), eq(reason));
            assertThat(events).contains(reason).doesNotContain(suggestions.getFirst(), "状态待恢复");
        }
    }

    private void assertOtherAppCanQueryAndGenerate() throws Exception {
        App other = app(42L, "html");
        other.setId(56L);
        when(appMapper.selectOneById(56L)).thenReturn(other);
        mockMvc.perform(get("/api/apps/56/messages").contextPath("/api").session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty());

        long otherId = IDS.incrementAndGet();
        when(persistence.start(56L, 42L, "另一个应用")).thenReturn(otherId);
        doAnswer(call -> {
            call.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(response(HTML, FinishReason.STOP));
            return null;
        }).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));
        var result = mockMvc.perform(post("/api/apps/56/generations").contextPath("/api").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"另一个应用\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(finish(result)).containsSubsequence("event:started", "event:completed");
        verify(persistence).complete(56L, 42L, otherId, HTML);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationDuringQualityCheckOrRepairStopsTheWorkflowWithoutReplacingTheVersion(boolean repairing) throws Exception {
        App app = app(42L, "vue_project");
        long previous = preparePreviousVueProject(app);
        when(builder.enabled()).thenReturn(true);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<StreamingChatResponseHandler> pending = new AtomicReference<>();
        AtomicInteger checks = new AtomicInteger();
        StreamingHandle handle = mock(StreamingHandle.class);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            boolean checking = request.toolSpecifications() == null || request.toolSpecifications().isEmpty();
            if (checking && !repairing || !checking && checks.get() == 1) {
                handler.onPartialResponse(new PartialResponse("处理中"), new PartialResponseContext(handle));
                pending.set(handler);
                waiting.countDown();
            } else if (checking) {
                checks.incrementAndGet();
                handler.onCompleteResponse(response("{\"isValid\":false,\"errors\":[\"按钮未响应\"],\"suggestions\":[]}", FinishReason.STOP));
            } else if (request.messages().getLast() instanceof ToolExecutionResultMessage) {
                handler.onCompleteResponse(response("源码完成", FinishReason.STOP));
            } else handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                    .aiMessage(AiMessage.from(writeFile("src/App.vue", "<template>候选</template>"))).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        MvcResult result = mockMvc.perform(generate().session(session())
                .content("{\"prompt\":\"摄影作品集\",\"qualityCheck\":true}")).andReturn();
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture.runAsync(heartbeatTick()).get(5, TimeUnit.SECONDS);
        mockMvc.perform(cancel(generationId).session(session())).andExpect(status().isOk());
        verify(handle).cancel();
        pending.get().onCompleteResponse(repairing
                ? ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                        .aiMessage(AiMessage.from(writeFile("src/App.vue", "<template>迟到的修改</template>"))).build()
                : response("{\"isValid\":true,\"errors\":[],\"suggestions\":[]}", FinishReason.STOP));
        assertThat(finish(result)).contains("event:cancelled", ":keepalive\n\n").doesNotContain("event:completed", "event:failed");
        verify(builder, never()).build(any(), any());
        verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
        assertThat(app.getGeneratedVersion()).isEqualTo(previous);
        assertThat(fileManager.readVueSource(42, 55, generationId, "src/App.vue")).isEqualTo("<template>候选</template>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "multi_file", "vue_project"})
    void silentGenerationSendsHeartbeatsForEveryMode(String mode) throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, mode));
        CountDownLatch waiting = new CountDownLatch(1);
        Answer<Void> silentModel = invocation -> {
            if (mode.equals("vue_project")) {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                PartialToolCallContext context = mock(PartialToolCallContext.class);
                when(context.streamingHandle()).thenReturn(mock(StreamingHandle.class));
                handler.onPartialToolCall(null, context);
            }
            waiting.countDown();
            return null;
        };
        doAnswer(silentModel).when(model).chat(anyList(), any(StreamingChatResponseHandler.class));
        doAnswer(silentModel).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        MvcResult result = mockMvc.perform(generate().session(session())).andReturn();
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        Runnable heartbeat = heartbeatTick();
        for (int tick = 0; tick < 10; tick++) heartbeat.run();
        verifyNoInteractions(heartbeatTask);
        mockMvc.perform(cancel(generationId).session(session())).andExpect(status().isOk());
        verify(heartbeatTask).cancel(false);
        heartbeat.run();
        String events = finish(result);
        assertThat(events).contains("event:started", "event:cancelled").doesNotContain("event:delta");
        assertThat(events.split(":keepalive\n\n", -1)).hasSize(11);
        verify(persistence).cancel(generationId, "");
    }

    private Runnable heartbeatTick() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(heartbeatScheduler).scheduleAtFixedRate(task.capture(), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        return task.getValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "cancelled"})
    void enabledVueBuildMustFinishBeforePublishingTheSuccessfulVersion(String outcome) throws Exception {
        when(appMapper.selectOneById(55L)).thenReturn(app(42L, "vue_project"));
        when(builder.enabled()).thenReturn(true);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(request.messages().getLast() instanceof ToolExecutionResultMessage
                    ? response("源码完成", FinishReason.STOP)
                    : ChatResponse.builder().finishReason(FinishReason.TOOL_EXECUTION)
                            .aiMessage(AiMessage.from(List.of(writeFile("src/App.vue", "<template>作品</template>")))).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
            var paths = (GeneratedFileManager.VueBuildPaths) invocation.getArgument(0);
            assertThat(paths.source().resolve("src/App.vue")).exists();
            // 另一个线程在构建期间发送心跳，验证构建没有占住会话锁。
            CompletableFuture.runAsync(heartbeatTick()).get(5, TimeUnit.SECONDS);
            if (outcome.equals("failure")) throw new java.io.IOException("private build detail");
            if (outcome.equals("cancelled")) mockMvc.perform(cancel(generationId).session(session())).andExpect(status().isOk());
            Files.writeString(paths.output().resolve("index.html"), HTML.replace("</head>",
                    "<script type=\"module\" src=\"./app.js\"></script></head>"));
            Files.writeString(paths.output().resolve("app.js"), "document.body.dataset.vue = 'ready'");
            return null;
        }).when(builder).build(any(), any());

        String events = finish(mockMvc.perform(generate().session(session())).andReturn());
        assertThat(events).contains("正在构建 Vue 工程", ":keepalive\n\n").doesNotContain("private build detail");
        verify(heartbeatTask).cancel(false);
        if (outcome.equals("success")) {
            assertThat(events).contains("event:completed", "Vue 工程构建完成");
            assertThat(fileManager.readVuePreview(42, 55, generationId)).contains("data:text/javascript");
            verify(persistence).complete(eq(55L), eq(42L), eq(generationId), any());
        } else {
            assertThat(events).contains(outcome.equals("failure") ? "event:failed" : "event:cancelled")
                    .doesNotContain("event:completed");
            verify(persistence, never()).complete(eq(55L), eq(42L), eq(generationId), any());
            assertThat(htmlPath().resolveSibling("preview.html")).doesNotExist();
        }
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
        mockMvc.perform(generate().session(session()).content("{\"prompt\":\"摄影作品集\",\"qualityCheck\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
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
