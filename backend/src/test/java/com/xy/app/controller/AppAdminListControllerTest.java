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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class AppAdminListControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    AppMapper apps;
    @MockitoBean
    UserMapper users;

    @BeforeEach
    void prepareAdmin() {
        when(users.selectOneById(42L)).thenReturn(user(UserRoleEnum.ADMIN));
    }

    @Test
    void letsAdminsCombineFiltersAndPageOtherOwnersSummariesWithoutPrivateContent() throws Exception {
        App app = app(9007199254740993L, 84L);
        app.setGeneratedVersion(101L);
        app.setPublishedVersion(89L);
        app.setDeployKey("a".repeat(32));
        app.setDeployedAt(LocalDateTime.of(2026, 11, 15, 11, 0));
        when(apps.paginate(eq(2), eq(1), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(app), 2, 1, 3));

        mockMvc.perform(query().session(session()).param("appName", " 博客 ").param("userId", "84")
                        .param("current", "2").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0]", aMapWithSize(7)))
                .andExpect(jsonPath("$.data.records[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.records[0].userId").value("84"))
                .andExpect(jsonPath("$.data.records[0].appName").value("个人博客"))
                .andExpect(jsonPath("$.data.records[0].codeGenType").value("multi_file"))
                .andExpect(jsonPath("$.data.records[0].createTime").value("2026-11-15T10:00:00"))
                .andExpect(jsonPath("$.data.records[0].updateTime").value("2026-11-15T11:00:00"))
                .andExpect(jsonPath("$.data.records[0].deployment.deployUrl")
                        .value("https://sites.example.test/" + "a".repeat(32) + "/"))
                .andExpect(jsonPath("$.data.records[0].deployment.publishedVersion").value("89"))
                .andExpect(jsonPath("$.data.records[0].initPrompt").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].generatedVersion").doesNotExist());

        ArgumentCaptor<QueryWrapper> filter = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(apps).paginate(eq(2), eq(1), filter.capture());
        assertThat(filter.getValue().toSQL()).contains("`userId` = 84", "`appName` LIKE '%博客%'",
                "`createTime` DESC", "`id` DESC").doesNotContain("`userId` = 42");
    }

    @Test
    void defaultsToAllOwnersAndReturnsAnEmptyListBeyondTheLastPage() throws Exception {
        when(apps.paginate(eq(1), eq(10), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(app(55L, 42L), app(56L, 84L)), 1, 10, 2));
        when(apps.paginate(eq(99), eq(10), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(), 99, 10, 2));

        mockMvc.perform(query().session(session()).param("appName", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].userId").value("42"))
                .andExpect(jsonPath("$.data.records[1].userId").value("84"))
                .andExpect(jsonPath("$.data.records[0].deployment").isEmpty());

        ArgumentCaptor<QueryWrapper> filter = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(apps).paginate(eq(1), eq(10), filter.capture());
        assertThat(filter.getValue().toSQL()).doesNotContain("LIKE", "userId");

        mockMvc.perform(query().session(session()).param("current", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void rejectsAnonymousQueriesBeforeReadingApps() throws Exception {
        mockMvc.perform(query())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(apps);
    }

    @Test
    void checksTheCurrentDatabaseRoleBeforeReadingApps() throws Exception {
        when(users.selectOneById(42L)).thenReturn(user(UserRoleEnum.USER));

        mockMvc.perform(query().session(session()).param("userRole", "admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        verify(users).selectOneById(42L);
        verifyNoInteractions(apps);
    }

    @Test
    void rejectsNonPositivePaginationAndOwnerIdsBeforeReadingApps() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(query().param("current", "0"),
                query().param("pageSize", "0"), query().param("userId", "0"))) {
            mockMvc.perform(request.session(session()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(apps);
    }

    private MockHttpServletRequestBuilder query() {
        return get("/api/apps").contextPath("/api");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }

    private User user(UserRoleEnum role) {
        User user = new User();
        user.setId(42L);
        user.setUserRole(role.getValue());
        return user;
    }

    private App app(long id, long userId) {
        App app = new App();
        app.setId(id);
        app.setUserId(userId);
        app.setAppName("个人博客");
        app.setInitPrompt("只允许创建者读取的初始需求");
        app.setCodeGenType("multi_file");
        app.setCreateTime(LocalDateTime.of(2026, 11, 15, 10, 0));
        app.setUpdateTime(LocalDateTime.of(2026, 11, 15, 11, 0));
        return app;
    }
}
