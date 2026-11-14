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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
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
class AppListControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    AppMapper apps;
    @MockitoBean
    UserMapper users;

    @BeforeEach
    void prepare() {
        User user = new User();
        user.setId(42L);
        when(users.selectOneById(42L)).thenReturn(user);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void filtersAndPagesOnlyTheCurrentUsersAppsIncludingPublishedDetails(String role) throws Exception {
        User user = new User();
        user.setId(42L);
        user.setUserRole(role);
        when(users.selectOneById(42L)).thenReturn(user);
        App app = new App();
        app.setId(55L);
        app.setUserId(42L);
        app.setAppName("个人博客");
        app.setInitPrompt("生成一个个人博客");
        app.setCodeGenType("multi_file");
        app.setPublishedVersion(89L);
        app.setDeployKey("a".repeat(32));
        app.setDeployedAt(LocalDateTime.of(2026, 11, 14, 10, 0));
        when(apps.paginate(eq(2), eq(1), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(app), 2, 1, 3));

        mockMvc.perform(query().session(session()).param("appName", " 博客 ")
                        .param("current", "2").param("pageSize", "1").param("userId", "84"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("55"))
                .andExpect(jsonPath("$.data.records[0].userId").value("42"))
                .andExpect(jsonPath("$.data.records[0].appName").value("个人博客"))
                .andExpect(jsonPath("$.data.records[0].deployment.deployUrl")
                        .value("https://sites.example.test/" + "a".repeat(32) + "/"));

        ArgumentCaptor<QueryWrapper> filter = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(apps).paginate(eq(2), eq(1), filter.capture());
        assertThat(filter.getValue().toSQL()).contains("`userId` = 42", "`appName` LIKE '%博客%'",
                "`createTime` DESC", "`id` DESC").doesNotContain("84");
    }

    @Test
    void defaultsToTheFirstPageAndTreatsBlankNameAsNoFilter() throws Exception {
        when(apps.paginate(eq(1), eq(10), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(), 1, 10, 0));

        mockMvc.perform(query().session(session()).param("appName", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));

        ArgumentCaptor<QueryWrapper> filter = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(apps).paginate(eq(1), eq(10), filter.capture());
        assertThat(filter.getValue().toSQL()).contains("`userId` = 42").doesNotContain("LIKE");
    }

    @Test
    void rejectsAnonymousQueriesBeforeReadingApps() throws Exception {
        mockMvc.perform(query())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(apps);
    }

    @Test
    void rejectsInvalidPageNumbersAndPageSizesBeforeReadingApps() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(query().param("current", "0"),
                query().param("pageSize", "0"), query().param("pageSize", "21"))) {
            mockMvc.perform(request.session(session()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(apps);
    }

    private MockHttpServletRequestBuilder query() {
        return get("/api/apps/mine").contextPath("/api");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }
}
