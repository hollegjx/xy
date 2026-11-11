package com.xy.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.common.UserConstant;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
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
class PreviewControllerTest {

    @TempDir
    static Path directory;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    GeneratedFileManager files;
    @Autowired
    ObjectMapper objectMapper;
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
        app.setGeneratedVersion(101L);
        when(apps.selectOneById(55L)).thenReturn(app);
        version = new ChatHistory();
        version.setId(101L);
        version.setAppId(55L);
        version.setUserId(42L);
        version.setMessageRole("assistant");
        version.setStatus("completed");
        when(messages.selectOneById(101L)).thenReturn(version);
    }

    @Test
    void servesOnlyTheSuccessfulSnapshotWithBrowserEnforcedIsolation() throws Exception {
        String html = new ClassPathResource("preview/isolation.html").getContentAsString(StandardCharsets.UTF_8);
        files.saveHtml(42, 55, 101, html);
        files.saveHtml(42, 55, 102, "<!DOCTYPE html><html><head></head><body>未提交为成功的新产物</body></html>");
        var response = mockMvc.perform(preview().session(session()).param("generationId", "102"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(html.strip()))
                .andExpect(header().string("Content-Security-Policy", containsString("sandbox allow-scripts;")))
                .andExpect(header().string("Content-Security-Policy", containsString("connect-src 'none'")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse();
        assertThat(response.getHeader("Content-Security-Policy")).doesNotContain("allow-same-origin");
        verify(messages).selectOneById(101L);
        // 浏览器检查复用真实 Controller 的响应，避免测试里手抄一份安全策略。
        Map<String, String> headers = new LinkedHashMap<>();
        response.getHeaderNames().forEach(name -> headers.put(name, response.getHeader(name)));
        Files.writeString(Path.of("target/preview-browser-fixture.json"), objectMapper.writeValueAsString(
                Map.of("headers", headers, "body", response.getContentAsString(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsAnonymousOtherOwnersAndMissingAppsBeforeReadingGeneratedContent() throws Exception {
        mockMvc.perform(preview()).andExpect(status().isUnauthorized());
        app.setUserId(84L);
        mockMvc.perform(preview().session(session())).andExpect(status().isForbidden());
        when(apps.selectOneById(55L)).thenReturn(null);
        mockMvc.perform(preview().session(session())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/apps/0/preview").contextPath("/api").session(session()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(messages);
    }

    @Test
    void rejectsMissingUnsuccessfulOrMismatchedVersionsAndReportsMissingFiles() throws Exception {
        app.setGeneratedVersion(null);
        mockMvc.perform(preview().session(session())).andExpect(status().isConflict());
        app.setGeneratedVersion(101L);
        for (String state : List.of("running", "failed", "cancelled")) {
            version.setStatus(state);
            mockMvc.perform(preview().session(session())).andExpect(status().isConflict());
        }
        version.setStatus("completed");
        version.setAppId(56L);
        mockMvc.perform(preview().session(session())).andExpect(status().isConflict());
        version.setAppId(55L);
        version.setUserId(84L);
        mockMvc.perform(preview().session(session())).andExpect(status().isConflict());
        version.setUserId(42L);
        version.setMessageRole("user");
        mockMvc.perform(preview().session(session())).andExpect(status().isConflict());
        version.setMessageRole("assistant");
        app.setCodeGenType("multi_file");
        mockMvc.perform(preview().session(session())).andExpect(status().isBadRequest());
        app.setCodeGenType("html");
        app.setGeneratedVersion(999L);
        when(messages.selectOneById(999L)).thenReturn(version);
        mockMvc.perform(preview().session(session())).andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("生成文件不存在，请重新生成"));
    }

    private MockHttpServletRequestBuilder preview() {
        return get("/api/apps/55/preview").contextPath("/api");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }
}
