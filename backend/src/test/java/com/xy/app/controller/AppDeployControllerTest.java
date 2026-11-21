package com.xy.app.controller;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.common.UserConstant;
import com.xy.common.ErrorCode;
import com.xy.config.DeploymentConfiguration;
import com.xy.exception.BusinessException;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.AppCoverRenderer;
import com.xy.client.ScreenshotService;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.awt.Color;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.servlet.context-path=/api", "xy.ai.enabled=false",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "xy.deployment.public-url=https://sites.example.test",
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class AppDeployControllerTest {

    @TempDir
    static Path directory;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    GeneratedFileManager files;
    @MockitoBean
    ScreenshotService covers;
    @MockitoSpyBean
    DeploymentConfiguration deployment;
    @MockitoBean
    AppMapper apps;
    @MockitoBean
    ChatHistoryMapper messages;
    @MockitoBean
    UserMapper users;
    private App app;
    private ChatHistory version;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("xy.generation.output-directory", () -> directory.resolve("generated").toString());
        registry.add("xy.deployment.directory", () -> directory.resolve("published").toString());
    }

    @BeforeEach
    void prepare() {
        User user = new User();
        user.setId(42L);
        user.setUserRole("admin");
        when(users.selectOneById(42L)).thenReturn(user);
        app = new App();
        app.setId(55L);
        app.setUserId(42L);
        app.setCodeGenType("html");
        when(apps.selectOneById(55L)).thenReturn(app);
        version = new ChatHistory();
        version.setAppId(55L);
        version.setUserId(42L);
        version.setMessageRole("assistant");
        version.setStatus("completed");
        useVersion(101);
        when(apps.assignDeployKey(anyLong(), anyLong(), anyString())).thenAnswer(call -> {
            app.setDeployKey(call.getArgument(2));
            return 1;
        });
        when(apps.updateDeployment(anyLong(), anyLong(), anyString(), anyLong(), any(LocalDateTime.class)))
                .thenAnswer(call -> {
                    app.setPublishedVersion(call.getArgument(3));
                    app.setDeployedAt(call.getArgument(4));
                    return 1;
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "multi_file", "vue_project"})
    void publishesASnapshotAndOnlyUpdatesTheStableAddressOnRepublish(String mode) throws Exception {
        app.setCodeGenType(mode);
        useVersion(switch (mode) { case "html" -> 101; case "multi_file" -> 201; default -> 401; });
        saveVersion("第一版");
        Files.writeString(directory.resolve("generated/42/55/" + version.getId() + "/private.txt"), "私有配置");

        mockMvc.perform(deploy().session(session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedVersion").value(version.getId().toString()))
                .andExpect(jsonPath("$.data.deployedAt").isString());
        String url = "https://sites.example.test/" + app.getDeployKey() + "/";
        Path index = publishedIndex();
        String firstSnapshot = Files.readString(index);
        assertThat(firstSnapshot).contains("第一版").doesNotContain("私有配置");
        try (var publishedFiles = Files.list(index.getParent())) {
            assertThat(publishedFiles.map(path -> path.getFileName().toString())).containsExactly("index.html");
        }
        if ("multi_file".equals(mode)) {
            assertThat(firstSnapshot).contains("data:text/css", "data:text/javascript");
            assertThat(Files.readString(directory.resolve("generated/42/55/201/index.html")))
                    .contains("style.css", "script.js");
        }

        useVersion(version.getId() + 1);
        saveVersion("第二版");
        assertThat(Files.readString(index)).isEqualTo(firstSnapshot);
        mockMvc.perform(deploy().session(session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deployUrl").value(url))
                .andExpect(jsonPath("$.data.publishedVersion").value(version.getId().toString()));
        assertThat(Files.readString(index)).contains("第二版").doesNotContain("第一版");
        mockMvc.perform(get("/api/apps/55").contextPath("/api").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deployment.deployUrl").value(url))
                .andExpect(jsonPath("$.data.deployment.publishedVersion").value(version.getId().toString()));
        verify(apps).assignDeployKey(55, 42, app.getDeployKey());
        if ("vue_project".equals(mode)) {
            String published = Files.readString(index);
            long publishedVersion = version.getId();
            useVersion(publishedVersion + 1);
            saveVueSource();
            mockMvc.perform(deploy().session(session())).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(50001));
            assertThat(Files.readString(index)).isEqualTo(published);
            assertThat(app.getPublishedVersion()).isEqualTo(publishedVersion);
        }
    }

    @Test
    void requiresTheOwnerEvenForAdministrators() throws Exception {
        mockMvc.perform(deploy()).andExpect(status().isUnauthorized());
        app.setUserId(84L);
        mockMvc.perform(deploy().session(session())).andExpect(status().isForbidden());
        when(apps.selectOneById(55L)).thenReturn(null);
        mockMvc.perform(deploy().session(session())).andExpect(status().isNotFound());
        verifyNoInteractions(messages);
        verify(apps, never()).assignDeployKey(anyLong(), anyLong(), anyString());
    }

    @Test
    void rejectsMissingOrUnsuccessfulVersionsBeforeAllocatingAnAddress() throws Exception {
        app.setGeneratedVersion(null);
        mockMvc.perform(deploy().session(session())).andExpect(status().isConflict());
        app.setGeneratedVersion(101L);
        for (String state : List.of("running", "failed", "cancelled")) {
            version.setStatus(state);
            mockMvc.perform(deploy().session(session())).andExpect(status().isConflict());
        }
        verify(apps, never()).assignDeployKey(anyLong(), anyLong(), anyString());
        verify(apps, never()).updateDeployment(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void missingSourceKeepsThePreviouslyPublishedSnapshot() throws Exception {
        useVersion(301);
        saveVersion("已发布页面");
        mockMvc.perform(deploy().session(session())).andExpect(status().isOk());
        String oldSnapshot = Files.readString(publishedIndex());
        app.setCodeGenType("multi_file");
        useVersion(302);
        saveVersion("缺少资源的页面");
        Files.delete(directory.resolve("generated/42/55/302/style.css"));

        mockMvc.perform(deploy().session(session())).andExpect(status().isNotFound());
        assertThat(Files.readString(publishedIndex())).isEqualTo(oldSnapshot);
        assertThat(app.getPublishedVersion()).isEqualTo(301L);
    }

    @Test
    void blankVueBuildKeepsThePreviouslyPublishedSnapshotAndVersion() throws Exception {
        app.setCodeGenType("vue_project");
        useVersion(501);
        saveVersion("可访问的旧作品");
        mockMvc.perform(deploy().session(session())).andExpect(status().isOk());
        Path index = publishedIndex();
        byte[] published = Files.readAllBytes(index);
        useVersion(502);
        saveVersion("新作品");
        Path preview = directory.resolve("generated/42/55/502/preview.html");
        for (String blank : List.of("", " \n\t")) {
            Files.writeString(preview, blank);
            mockMvc.perform(deploy().session(session())).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(50000))
                    .andExpect(jsonPath("$.message").value("该版本的 Vue 构建产物为空，请重新生成"));
            assertThat(Files.readAllBytes(index)).containsExactly(published);
            assertThat(app.getPublishedVersion()).isEqualTo(501L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "multi_file"})
    void unconfiguredSiteTakesPrecedenceOverMissingNativeFiles(String mode) throws Exception {
        app.setCodeGenType(mode);
        useVersion(601);
        String message = "尚未配置独立静态站点，请先配置发布目录和地址";
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, message)).when(deployment).requireConfigured();
        mockMvc.perform(deploy().session(session())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50300)).andExpect(jsonPath("$.message").value(message));
        verify(apps, never()).assignDeployKey(anyLong(), anyLong(), anyString());
    }

    private void useVersion(long id) {
        app.setGeneratedVersion(id);
        version.setId(id);
        when(messages.selectOneById(id)).thenReturn(version);
    }

    @Test
    void refreshesTheFeaturedCoverWithThePublishedSnapshotAndKeepsPublishingIfCaptureFails() throws Exception {
        app.setFeatured(true);
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(app));
        for (long id : List.of(801L, 802L, 803L)) {
            useVersion(id);
            saveVersion("作品 " + id);
            var html = files.readHtml(42, 55, id);
            byte[] cover = { (byte) 0xff, (byte) 0xd8, (byte) id };
            if (id == 803) when(covers.render(html)).thenThrow(new IOException("截图不可用"));
            else when(covers.render(html)).thenReturn(cover);
            mockMvc.perform(deploy().session(session())).andExpect(status().isOk());
            assertThat(Files.readString(publishedIndex())).isEqualTo(html);
            var url = "/api/apps/55/covers/" + id;
            if (id == 803) {
                mockMvc.perform(get("/api/apps/featured").contextPath("/api")).andExpect(jsonPath("$.data[0].coverUrl").isEmpty());
                mockMvc.perform(get(url).contextPath("/api")).andExpect(status().isNotFound());
            } else {
                await().untilAsserted(() -> mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                        .andExpect(jsonPath("$.data[0].coverUrl").value(url)));
                var response = mockMvc.perform(get(url).contextPath("/api")).andExpect(status().isOk())
                        .andReturn().getResponse();
                assertThat(response.getContentType()).isEqualTo("image/jpeg");
                assertThat(response.getContentAsByteArray()).isEqualTo(cover);
                app.setFeatured(false);
                mockMvc.perform(get(url).contextPath("/api")).andExpect(status().isNotFound());
                app.setFeatured(true);
            }
            mockMvc.perform(get("/api/apps/55/covers/" + (id - 1)).contextPath("/api")).andExpect(status().isNotFound());
        }
    }

    @Test
    void returnsBeforeCaptureAndAnOlderCaptureCannotReplaceTheNewCover() throws Exception {
        app.setFeatured(true);
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(app));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        useVersion(811);
        saveVersion("旧快照");
        when(covers.render(files.readHtml(42, 55, 811))).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("发布没有在截图完成前返回");
            return new byte[]{1};
        });
        try {
            mockMvc.perform(deploy().session(session())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.publishedVersion").value("811"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                    .andExpect(jsonPath("$.data[0].coverUrl").isEmpty());
            useVersion(812);
            saveVersion("新快照");
            when(covers.render(files.readHtml(42, 55, 812))).thenReturn(new byte[]{2});
            mockMvc.perform(deploy().session(session())).andExpect(status().isOk());
            await().until(() -> files.hasCover(42, 55, 812));
        } finally {
            release.countDown();
        }
        await().until(() -> files.hasCover(42, 55, 811));
        mockMvc.perform(get("/api/apps/55/covers/811").contextPath("/api")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                .andExpect(jsonPath("$.data[0].coverUrl").value("/api/apps/55/covers/812"));
        assertThat(files.readCover(42, 55, 812)).containsExactly(2);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XY_TEST_DOCKER_COVER", matches = "true")
    void rendersRepublishedFeaturedWorkInDocker() throws Exception {
        var renderer = new AppCoverRenderer(System.getenv().getOrDefault("XY_TEST_DOCKER_CLI", "docker"));
        when(covers.render(anyString())).thenAnswer(call -> renderer.render(call.getArgument(0)));
        app.setFeatured(true);
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(app));
        for (long id : List.of(901L, 902L)) {
            var color = id == 901 ? Color.RED : Color.BLUE;
            String css = id == 901 ? "red" : "blue";
            useVersion(id);
            saveVersion("<script>document.body.style.background='" + css + "'</script>");
            mockMvc.perform(deploy().session(session())).andExpect(status().isOk());
            String url = "/api/apps/55/covers/" + id;
            await().atMost(25, TimeUnit.SECONDS).untilAsserted(() -> mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                    .andExpect(jsonPath("$.data[0].coverUrl").value(url)));
            var response = mockMvc.perform(get(url).contextPath("/api")).andExpect(status().isOk())
                    .andReturn().getResponse();
            assertThat(response.getContentType()).isEqualTo("image/jpeg");
            var image = ImageIO.read(new ByteArrayInputStream(response.getContentAsByteArray()));
            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isEqualTo(960);
            assertThat(image.getHeight()).isEqualTo(540);
            var pixel = new Color(image.getRGB(480, 270));
            assertThat(Math.abs(pixel.getRed() - color.getRed())).isLessThan(5);
            assertThat(Math.abs(pixel.getBlue() - color.getBlue())).isLessThan(5);
            assertThat(pixel.getGreen()).isLessThan(5);
        }
    }

    private void saveVersion(String body) throws Exception {
        String html = "<!DOCTYPE html><html><head></head><body>" + body + "</body></html>";
        if ("html".equals(app.getCodeGenType())) files.saveHtml(42, 55, version.getId(), html);
        else if ("vue_project".equals(app.getCodeGenType())) {
            saveVueSource();
            var build = files.prepareVueBuild(42, 55, version.getId());
            Files.writeString(build.output().resolve("index.html"),
                    html.replace("</body>", "<script src=\"./app.js\"></script></body>"));
            Files.writeString(build.output().resolve("app.js"), "document.body.dataset.ready = 'yes';");
            files.saveVuePreview(42, 55, version.getId(), build);
        } else files.saveMultiFile(42, 55, version.getId(), Map.of("index.html",
                html.replace("</head>", "<link rel=\"stylesheet\" href=\"style.css\"></head>")
                        .replace("</body>", "<script src=\"script.js\"></script></body>"),
                "style.css", "body { color: black; }", "script.js", "window.greeting = '你好';"));
    }

    private void saveVueSource() throws Exception {
        files.writeVueSource(42, 55, version.getId(), "src/App.vue", "<template>源码</template>");
        files.finishVueProject(42, 55, version.getId(), Map.of("index.html", "<div id=\"app\"></div>",
                "src/main.js", "import App from './App.vue'", "package.json", "{}", "vite.config.js", "export default {}"));
    }

    private Path publishedIndex() {
        return directory.resolve("published/" + app.getDeployKey() + "/index.html");
    }

    private MockHttpServletRequestBuilder deploy() {
        return post("/api/apps/55/deploy").contextPath("/api");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }
}
