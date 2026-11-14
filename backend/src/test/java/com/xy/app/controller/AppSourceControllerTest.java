package com.xy.app.controller;

import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.common.UserConstant;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.servlet.context-path=/api", "xy.ai.enabled=false",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class AppSourceControllerTest {

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
    static void outputDirectory(DynamicPropertyRegistry registry) {
        registry.add("xy.generation.output-directory", () -> directory.toString());
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
        useVersion(101L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "multi_file"})
    void downloadsOnlyTheLatestSuccessfulSourceWithOriginalNamesAndContents(String mode) throws Exception {
        app.setCodeGenType(mode);
        useVersion("html".equals(mode) ? 101L : 201L);
        Map<String, String> expected = "html".equals(mode) ? Map.of("index.html", html("成功版本 🌟")) : multiFile();
        if ("html".equals(mode)) files.saveHtml(42, 55, version.getId(), expected.get("index.html"));
        else files.saveMultiFile(42, 55, version.getId(), expected);
        files.saveHtml(42, 55, version.getId() + 1, html("尚未成功的新版本"));
        Files.writeString(directory.resolve("42/55/" + version.getId() + "/application.yml"), "非生成文件");

        byte[] archive = mockMvc.perform(source().session(session())
                        .param("generationId", Long.toString(version.getId() + 1)).param("path", "application.yml"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"xy-app-55.zip\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, String> actual = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertThat(actual).doesNotContainKey(entry.getName());
                actual.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void rejectsAnonymousOtherOwnersAndMissingAppsBeforeReadingSource() throws Exception {
        mockMvc.perform(source()).andExpect(status().isUnauthorized());
        app.setUserId(84L);
        mockMvc.perform(source().session(session())).andExpect(status().isForbidden());
        when(apps.selectOneById(55L)).thenReturn(null);
        mockMvc.perform(source().session(session())).andExpect(status().isNotFound());
        verifyNoInteractions(messages);
    }

    @Test
    void requiresASuccessfulAssistantVersionBelongingToTheAppAndOwner() throws Exception {
        app.setGeneratedVersion(null);
        mockMvc.perform(source().session(session())).andExpect(status().isConflict());
        app.setGeneratedVersion(101L);
        when(messages.selectOneById(101L)).thenReturn(null);
        mockMvc.perform(source().session(session())).andExpect(status().isConflict());
        when(messages.selectOneById(101L)).thenReturn(version);
        for (String state : List.of("running", "failed", "cancelled")) {
            version.setStatus(state);
            mockMvc.perform(source().session(session())).andExpect(status().isConflict());
        }
        version.setStatus("completed");
        version.setAppId(56L);
        mockMvc.perform(source().session(session())).andExpect(status().isConflict());
        version.setAppId(55L);
        version.setUserId(84L);
        mockMvc.perform(source().session(session())).andExpect(status().isConflict());
        version.setUserId(42L);
        version.setMessageRole("user");
        mockMvc.perform(source().session(session())).andExpect(status().isConflict());
    }

    @Test
    void returnsJsonErrorsForIncompleteFilesAndRefusesLinkedFiles() throws Exception {
        app.setCodeGenType("multi_file");
        useVersion(301L);
        files.saveMultiFile(42, 55, 301, multiFile());
        Path css = directory.resolve("42/55/301/style.css");
        Files.delete(css);
        mockMvc.perform(source().session(session())).andExpect(status().isNotFound())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.message").value("生成文件不存在，请重新生成"));
        Path outside = Files.writeString(directory.resolve("unrelated.css"), "其他文件");
        Files.createSymbolicLink(css, outside);
        mockMvc.perform(source().session(session())).andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.message").value("读取源码失败，请检查生成文件"));
    }

    private void useVersion(long id) {
        app.setGeneratedVersion(id);
        version.setId(id);
        when(messages.selectOneById(id)).thenReturn(version);
    }

    private String html(String body) {
        return "<!DOCTYPE html><html><head></head><body>" + body + "</body></html>";
    }

    private Map<String, String> multiFile() {
        return Map.of("index.html", "<!DOCTYPE html><html><head><link rel=\"stylesheet\" href=\"style.css\">"
                        + "</head><body>源码 🌟<script src=\"script.js\"></script></body></html>",
                "style.css", "body { color: black; }\n", "script.js", "console.log('保留原始源码 🌟');\n");
    }

    private MockHttpServletRequestBuilder source() {
        return get("/api/apps/55/source").contextPath("/api");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }
}
