package com.xy.app.controller;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.common.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
class AppAdminUpdateControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    AppMapper apps;
    @MockitoBean
    UserMapper users;
    private App app;

    @BeforeEach
    void prepare() {
        when(users.selectOneById(42L)).thenReturn(user(42L, UserRoleEnum.ADMIN));
        when(users.selectOneById(84L)).thenReturn(user(84L, UserRoleEnum.USER));
        app = new App();
        app.setId(55L);
        app.setUserId(84L);
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
    void adminRenamesAnotherOwnersAppAndBothQueriesReadTheSavedName() throws Exception {
        when(apps.updateAppName(55L, 84L, "星云作品集")).thenAnswer(invocation -> {
            app.setAppName(invocation.getArgument(2));
            return 1;
        });
        when(apps.paginate(eq(1), eq(10), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(app), 1, 10, 1));

        mockMvc.perform(update(55L).session(session(42L)).content("""
                        {"appName":"星云作品集", "userId":"42", "initPrompt":"替换需求",
                         "codeGenType":"html", "generatedVersion":"1", "publishedVersion":"1",
                         "deployKey":"another", "isDelete":1}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/api/apps").contextPath("/api").session(session(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].appName").value("星云作品集"))
                .andExpect(jsonPath("$.data.records[0].userId").value("84"));
        mockMvc.perform(get("/api/apps/55").contextPath("/api").session(session(84L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appName").value("星云作品集"))
                .andExpect(jsonPath("$.data.userId").value("84"))
                .andExpect(jsonPath("$.data.initPrompt").value("生成一个个人博客"))
                .andExpect(jsonPath("$.data.codeGenType").value("multi_file"))
                .andExpect(jsonPath("$.data.deployment.publishedVersion").value("89"))
                .andExpect(jsonPath("$.data.deployment.deployUrl")
                        .value("https://sites.example.test/" + "a".repeat(32) + "/"));
        mockMvc.perform(update(55L).session(session(42L)).content("{\"appName\":\"星云作品集\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(apps).updateAppName(55L, 84L, "星云作品集");
        assertThat(app.getGeneratedVersion()).isEqualTo(90L);
    }

    @Test
    void rejectsAnonymousUpdatesBeforeAccessingApps() throws Exception {
        mockMvc.perform(update(55L).content("{\"appName\":\"新名称\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(apps);
    }

    @Test
    void rejectsAnOwnerWhoseCurrentDatabaseRoleIsNotAdmin() throws Exception {
        app.setUserId(42L);
        when(users.selectOneById(42L)).thenReturn(user(42L, UserRoleEnum.USER));

        mockMvc.perform(update(55L).session(session(42L))
                        .content("{\"appName\":\"新名称\",\"userRole\":\"admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        verify(users).selectOneById(42L);
        verifyNoInteractions(apps);
    }

    @Test
    void rejectsMissingAppsWithoutWriting() throws Exception {
        when(apps.selectOneById(55L)).thenReturn(null);

        mockMvc.perform(update(55L).session(session(42L)).content("{\"appName\":\"新名称\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        verify(apps).selectOneById(55L);
        verifyNoMoreInteractions(apps);
    }

    @Test
    void rejectsInvalidNamesAndNonPositiveIdsBeforeAccessingApps() throws Exception {
        for (String body : List.of("{}", "{\"appName\":\"   \"}",
                "{\"appName\":\"" + "a".repeat(129) + "\"}")) {
            mockMvc.perform(update(55L).session(session(42L)).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        mockMvc.perform(update(0L).session(session(42L)).content("{\"appName\":\"新名称\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(apps);
    }

    @Test
    void adminSetsAndClearsFeaturedAndQueriesReadTheSavedState() throws Exception {
        when(apps.paginate(eq(1), eq(10), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(app), 1, 10, 1));
        assertThat(app.getFeatured()).isFalse();
        for (boolean featured : List.of(true, false)) {
            when(apps.updateFeatured(55L, 84L, featured)).thenAnswer(invocation -> {
                app.setFeatured(invocation.getArgument(2));
                return 1;
            });
            for (int attempt = 0; attempt < 2; attempt++) {
                mockMvc.perform(featured(55L).session(session(42L))
                                .content("{\"featured\":" + featured + ",\"userId\":\"42\",\"appName\":\"替换名称\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
            }
            mockMvc.perform(get("/api/apps").contextPath("/api").session(session(42L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.records[0].featured").value(featured));
            verify(apps).updateFeatured(55L, 84L, featured);
        }
        assertThat(app.getAppName()).isEqualTo("旧应用");
        assertThat(app.getUserId()).isEqualTo(84L);
        assertThat(app.getInitPrompt()).isEqualTo("生成一个个人博客");
        assertThat(app.getGeneratedVersion()).isEqualTo(90L);
        assertThat(app.getPublishedVersion()).isEqualTo(89L);
        assertThat(app.getDeployKey()).isEqualTo("a".repeat(32));
    }

    @Test
    void featuredRequiresCurrentAdminRoleEvenWhenTheCallerOwnsTheApp() throws Exception {
        mockMvc.perform(featured(55L).content("{\"featured\":true}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        app.setUserId(42L);
        when(users.selectOneById(42L)).thenReturn(user(42L, UserRoleEnum.USER));
        mockMvc.perform(featured(55L).session(session(42L)).content("{\"featured\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        verifyNoInteractions(apps);
    }

    @Test
    void unpublishedAppCannotBeFeaturedButCanRemainUnfeatured() throws Exception {
        app.setPublishedVersion(null);
        mockMvc.perform(featured(55L).session(session(42L)).content("{\"featured\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("仅已发布应用可设为精选"));
        mockMvc.perform(featured(55L).session(session(42L)).content("{\"featured\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        assertThat(app.getFeatured()).isFalse();
        verify(apps, times(2)).selectOneById(55L);
        verifyNoMoreInteractions(apps);
    }

    @Test
    void featuredRejectsMissingOrDeletedApp() throws Exception {
        when(apps.selectOneById(55L)).thenReturn(null);
        mockMvc.perform(featured(55L).session(session(42L)).content("{\"featured\":true}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        verify(apps).selectOneById(55L);
        verifyNoMoreInteractions(apps);
    }

    @Test
    void featuredRequiresAValueAndPositiveId() throws Exception {
        for (String body : List.of("{}", "{\"featured\":null}")) {
            mockMvc.perform(featured(55L).session(session(42L)).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        }
        mockMvc.perform(featured(0L).session(session(42L)).content("{\"featured\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(apps);
    }

    private MockHttpServletRequestBuilder featured(long id) {
        return patch("/api/admin/apps/" + id + "/featured")
                .contextPath("/api").contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder update(long id) {
        return patch("/api/admin/apps/" + id).contextPath("/api").contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpSession session(long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, userId);
        return session;
    }

    private User user(long id, UserRoleEnum role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role.getValue());
        return user;
    }
}
