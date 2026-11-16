package com.xy.app.controller;

import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.common.UserConstant;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.servlet.context-path=/api", "xy.ai.enabled=false",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "xy.deployment.public-url=",
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class AppDeleteControllerTest {

    private static final String HTML = "<!DOCTYPE html><html><head></head><body>应用页面</body></html>";
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
    private User user;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("xy.generation.output-directory", () -> directory.resolve("generated").toString());
        registry.add("xy.deployment.directory", () -> directory.resolve("published").toString());
    }

    @BeforeEach
    void prepare() {
        user = new User();
        user.setId(42L);
        user.setUserRole("user");
        when(users.selectOneById(42L)).thenReturn(user);
        app = new App();
        app.setId(55L);
        app.setUserId(42L);
        app.setCodeGenType("html");
        app.setIsDelete(0);
        when(apps.selectOneById(55L)).thenAnswer(call -> app.getIsDelete() == 1 ? null : app);
        when(apps.markDeleted(55L, 42L)).thenAnswer(call -> {
            if (app.getDeployKey() != null) assertThat(publishedIndex()).doesNotExist();
            app.setIsDelete(1);
            return 1;
        });
    }

    @Test
    void ownerRemovesPublicPageAndLosesAccessWhilePrivateFilesAreRetained() throws Exception {
        app.setDeployKey("a".repeat(32));
        files.saveHtml(42, 55, 101, HTML);
        files.publishHtml(directory.resolve("published"), app.getDeployKey(), HTML);
        files.publishHtml(directory.resolve("published"), "b".repeat(32), HTML);

        mockMvc.perform(remove().session(session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").value(true));

        assertThat(app.getIsDelete()).isEqualTo(1);
        assertThat(publishedIndex()).doesNotExist();
        assertThat(Files.readString(directory.resolve("generated/42/55/101/index.html"))).isEqualTo(HTML);
        assertThat(Files.readString(directory.resolve("published/" + "b".repeat(32) + "/index.html")))
                .isEqualTo(HTML);
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/apps/55"), get("/api/apps/55/messages"), get("/api/apps/55/preview"),
                get("/api/apps/55/source"), post("/api/apps/55/deploy"),
                post("/api/apps/55/generations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"修改标题\"}"))) {
            mockMvc.perform(request.contextPath("/api").session(session()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        }
        mockMvc.perform(remove().session(session())).andExpect(status().isNotFound());
        verify(apps).markDeleted(55L, 42L);
        verifyNoInteractions(messages);
    }

    @Test
    void deletesWithoutAPublishedEntryOrConfiguredPublicUrl() throws Exception {
        mockMvc.perform(remove().session(session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        app.setIsDelete(0);
        app.setDeployKey("c".repeat(32));
        mockMvc.perform(remove().session(session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        assertThat(publishedIndex().getParent()).doesNotExist();
    }

    @Test
    void rejectsAnonymousAndOtherUsersIncludingAdministratorsWithoutRemovingFiles() throws Exception {
        mockMvc.perform(remove()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(apps);

        app.setUserId(84L);
        app.setDeployKey("d".repeat(32));
        Files.createDirectories(publishedIndex().getParent());
        Files.writeString(publishedIndex(), HTML);
        for (String role : List.of("user", "admin")) {
            user.setUserRole(role);
            mockMvc.perform(remove().session(session())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));
        }
        assertThat(Files.readString(publishedIndex())).isEqualTo(HTML);
        verify(apps, never()).markDeleted(anyLong(), anyLong());
    }

    @Test
    void failedPublicRemovalKeepsAppAccessibleAndDoesNotFollowLinks() throws Exception {
        app.setDeployKey("e".repeat(32));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("index.html"), HTML);
        Files.createDirectories(directory.resolve("published"));
        Files.createSymbolicLink(publishedIndex().getParent(), outside);

        user.setUserRole("admin");
        for (String path : List.of("/api/apps/55", "/api/admin/apps/55")) {
            mockMvc.perform(delete(path).contextPath("/api").session(session()))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(50000))
                    .andExpect(jsonPath("$.message").value("移除发布页面失败，应用尚未删除，请重试"));
        }
        mockMvc.perform(get("/api/apps/55").contextPath("/api").session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("55"));
        assertThat(app.getIsDelete()).isZero();
        assertThat(Files.readString(outside.resolve("index.html"))).isEqualTo(HTML);
        verify(apps, never()).markDeleted(anyLong(), anyLong());
    }

    @Test
    void administratorDeletesAnotherUsersAppAndRemovesOnlyItsPublicEntry() throws Exception {
        user.setUserRole("admin");
        app.setUserId(84L);
        app.setDeployKey("f".repeat(32));
        files.saveHtml(84, 55, 101, HTML);
        files.publishHtml(directory.resolve("published"), app.getDeployKey(), HTML);
        when(apps.markDeleted(55L, 84L)).thenAnswer(call -> {
            assertThat(publishedIndex()).doesNotExist();
            app.setIsDelete(1);
            return 1;
        });

        mockMvc.perform(delete("/api/admin/apps/55").contextPath("/api").session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));

        assertThat(app.getIsDelete()).isEqualTo(1);
        assertThat(publishedIndex()).doesNotExist();
        assertThat(Files.readString(directory.resolve("generated/84/55/101/index.html"))).isEqualTo(HTML);
        mockMvc.perform(delete("/api/admin/apps/55").contextPath("/api").session(session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        verify(apps).markDeleted(55L, 84L);
        verify(apps, never()).markDeleted(55L, 42L);
        verifyNoInteractions(messages);
    }

    @Test
    void adminEntryRejectsAnonymousAndOrdinaryOwnerBeforeAccessingApps() throws Exception {
        mockMvc.perform(delete("/api/admin/apps/55").contextPath("/api"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(delete("/api/admin/apps/55").contextPath("/api").session(session()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        verifyNoInteractions(apps);
    }

    @Test
    void adminEntryRejectsMissingAppAndNonPositiveId() throws Exception {
        user.setUserRole("admin");
        mockMvc.perform(delete("/api/admin/apps/99").contextPath("/api").session(session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(delete("/api/admin/apps/0").contextPath("/api").session(session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        verify(apps, never()).markDeleted(anyLong(), anyLong());
    }

    private Path publishedIndex() {
        return directory.resolve("published/" + app.getDeployKey() + "/index.html");
    }

    private MockHttpServletRequestBuilder remove() {
        return delete("/api/apps/55").contextPath("/api");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }
}
