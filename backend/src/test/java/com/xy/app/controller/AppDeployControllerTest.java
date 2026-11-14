package com.xy.app.controller;

import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.common.UserConstant;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
    @ValueSource(strings = {"html", "multi_file"})
    void publishesASnapshotAndOnlyUpdatesTheStableAddressOnRepublish(String mode) throws Exception {
        app.setCodeGenType(mode);
        useVersion("html".equals(mode) ? 101 : 201);
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

    private void useVersion(long id) {
        app.setGeneratedVersion(id);
        version.setId(id);
        when(messages.selectOneById(id)).thenReturn(version);
    }

    private void saveVersion(String body) throws Exception {
        String html = "<!DOCTYPE html><html><head></head><body>" + body + "</body></html>";
        if ("html".equals(app.getCodeGenType())) files.saveHtml(42, 55, version.getId(), html);
        else files.saveMultiFile(42, 55, version.getId(), Map.of("index.html",
                html.replace("</head>", "<link rel=\"stylesheet\" href=\"style.css\"></head>")
                        .replace("</body>", "<script src=\"script.js\"></script></body>"),
                "style.css", "body { color: black; }", "script.js", "window.greeting = '你好';"));
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
