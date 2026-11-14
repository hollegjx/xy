package com.xy.app.controller;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.common.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.servlet.context-path=/api", "xy.ai.enabled=false",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "xy.deployment.public-url=https://sites.example.test",
        "xy.deployment.platform-origin=https://xy.example.test",
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class AppUpdateControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    AppMapper apps;
    @MockitoBean
    UserMapper users;
    private App app;
    private User user;

    @BeforeEach
    void prepare() {
        user = new User();
        user.setId(42L);
        user.setUserRole("user");
        when(users.selectOneById(42L)).thenReturn(user);
        app = new App();
        app.setId(55L);
        app.setUserId(42L);
        app.setAppName("旧应用");
        app.setInitPrompt("生成一个个人博客");
        app.setCodeGenType("multi_file");
        app.setGeneratedVersion(90L);
        app.setPublishedVersion(89L);
        app.setDeployKey("a".repeat(32));
        app.setDeployedAt(LocalDateTime.of(2026, 11, 14, 10, 0));
        when(apps.selectOneById(55L)).thenReturn(app);
    }

    @Test
    void ownerRenamesAppAndReadsTheNameWithoutChangingOtherInformation() throws Exception {
        when(apps.updateAppName(55L, 42L, "我的个人博客")).thenAnswer(invocation -> {
            app.setAppName(invocation.getArgument(2));
            return 1;
        });
        when(apps.paginate(eq(1), eq(10), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(app), 1, 10, 1));

        mockMvc.perform(update().session(session()).content("""
                        {"appName":"我的个人博客", "userId":"84", "initPrompt":"替换需求",
                         "codeGenType":"html", "generatedVersion":"1", "publishedVersion":"1",
                         "deployKey":"another"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/api/apps/55").contextPath("/api").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appName").value("我的个人博客"))
                .andExpect(jsonPath("$.data.userId").value("42"))
                .andExpect(jsonPath("$.data.initPrompt").value("生成一个个人博客"))
                .andExpect(jsonPath("$.data.codeGenType").value("multi_file"))
                .andExpect(jsonPath("$.data.deployment.publishedVersion").value("89"))
                .andExpect(jsonPath("$.data.deployment.deployUrl")
                        .value("https://sites.example.test/" + "a".repeat(32) + "/"));
        mockMvc.perform(get("/api/apps/mine").contextPath("/api").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].appName").value("我的个人博客"));
        mockMvc.perform(update().session(session()).content("{\"appName\":\"我的个人博客\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(apps).updateAppName(55L, 42L, "我的个人博客");
        assertThat(app.getGeneratedVersion()).isEqualTo(90L);
    }

    @Test
    void rejectsMissingBlankOrTooLongNamesBeforeAccessingApps() throws Exception {
        for (String body : List.of("{}", "{\"appName\":\"   \"}",
                "{\"appName\":\"" + "a".repeat(129) + "\"}")) {
            mockMvc.perform(update().session(session()).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(apps);
    }

    @Test
    void rejectsAnonymousUpdatesBeforeAccessingApps() throws Exception {
        mockMvc.perform(update().content("{\"appName\":\"新名称\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(apps);
    }

    @Test
    void rejectsOtherUsersIncludingAdministrators() throws Exception {
        app.setUserId(84L);
        for (String role : List.of("user", "admin")) {
            user.setUserRole(role);
            mockMvc.perform(update().session(session()).content("{\"appName\":\"新名称\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));
        }
        verify(apps, never()).updateAppName(anyLong(), anyLong(), anyString());
    }

    @Test
    void rejectsMissingAppsWithoutWriting() throws Exception {
        when(apps.selectOneById(55L)).thenReturn(null);
        mockMvc.perform(update().session(session()).content("{\"appName\":\"新名称\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        verify(apps).selectOneById(55L);
        verifyNoMoreInteractions(apps);
    }

    private MockHttpServletRequestBuilder update() {
        return patch("/api/apps/55").contextPath("/api").contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }
}
