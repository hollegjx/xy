package com.xy.app.controller;

import com.mybatisflex.core.query.CPI;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.app.service.AppService;
import com.xy.common.UserConstant;
import com.xy.config.DeploymentConfiguration;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class AppFeaturedControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    AppService appService;
    @MockitoBean
    AppMapper apps;
    @MockitoBean
    UserMapper users;
    @MockitoBean
    DeploymentConfiguration deployment;

    @BeforeEach
    void clearPreviousTestCards() {
        appService.invalidateFeaturedApps();
    }

    @Test
    void letsVisitorsReadOnlyPublicSummariesFromTheSixLatestPublishedFeaturedApps() throws Exception {
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(app()));
        when(deployment.siteUrl("a".repeat(32))).thenReturn("https://sites.example.test/" + "a".repeat(32) + "/");

        mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$.data[0].coverUrl").isEmpty())
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].appName").value("摄影作品集"))
                .andExpect(jsonPath("$.data[0].codeGenType").value("multi_file"))
                .andExpect(jsonPath("$.data[0].deployUrl").value("https://sites.example.test/" + "a".repeat(32) + "/"))
                .andExpect(jsonPath("$.data[0].initPrompt").doesNotExist())
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());

        home().andExpect(jsonPath("$.data[0].appName").value("摄影作品集"));
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(apps).selectListByQuery(query.capture());
        assertThat(query.getValue().toSQL()).contains("`featured`", "`publishedVersion` IS NOT NULL",
                "`deployKey` IS NOT NULL", "`deployedAt` DESC", "`id` DESC", "LIMIT 6");
        assertThat(CPI.getValueArray(query.getValue())).contains(true);
        verifyNoInteractions(users);
    }

    @Test
    void omitsWorksWithoutAConfiguredPublicAddress() throws Exception {
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(app()));
        mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void returnsAnEmptyListWhenNoFeaturedWorksMatch() throws Exception {
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(deployment, users);
    }

    @Test
    void cachesTheEmptyHomeUntilAModeratorFeaturesOrWithdrawsAWork() throws Exception {
        App work = storedWork();
        work.setFeatured(false);
        var admin = session(true);
        when(apps.updateFeatured(eq(work.getId()), eq(work.getUserId()), anyBoolean())).thenAnswer(call -> {
            work.setFeatured(call.getArgument(2));
            return 1;
        });
        home().andExpect(jsonPath("$.data").isEmpty());
        home().andExpect(jsonPath("$.data").isEmpty());
        for (boolean feature : List.of(true, false)) {
            mockMvc.perform(patch("/api/admin/apps/{id}/featured", work.getId()).contextPath("/api").session(admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"featured\":" + feature + "}"))
                    .andExpect(status().isOk());
            home().andExpect(jsonPath("$.data.length()").value(feature ? 1 : 0));
        }
        verify(apps, times(3)).selectListByQuery(any(QueryWrapper.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void homeReflectsRenameAndDeletionByOwnerOrAdmin(boolean admin, @TempDir Path directory) throws Exception {
        App work = storedWork();
        var caller = session(admin);
        String path = admin ? "/api/admin/apps/{id}" : "/api/apps/{id}";
        when(deployment.directory()).thenReturn(directory);
        when(apps.updateAppName(work.getId(), work.getUserId(), "新作品名")).thenAnswer(call -> {
            work.setAppName(call.getArgument(2));
            return 1;
        });
        when(apps.markDeleted(work.getId(), work.getUserId())).thenAnswer(call -> {
            work.setIsDelete(1);
            return 1;
        });
        home().andExpect(jsonPath("$.data[0].appName").value("摄影作品集"));
        mockMvc.perform(patch(path, work.getId()).contextPath("/api").session(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"appName\":\"新作品名\"}")).andExpect(status().isOk());
        home().andExpect(jsonPath("$.data[0].appName").value("新作品名"));
        mockMvc.perform(delete(path, work.getId()).contextPath("/api").session(caller)).andExpect(status().isOk());
        home().andExpect(jsonPath("$.data").isEmpty());
    }

    private ResultActions home() throws Exception {
        return mockMvc.perform(get("/api/apps/featured").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }

    private App storedWork() {
        App work = app();
        work.setIsDelete(0);
        when(apps.selectOneById(work.getId())).thenReturn(work);
        when(apps.selectListByQuery(any(QueryWrapper.class))).thenAnswer(call ->
                work.getFeatured() && work.getIsDelete() == 0 ? List.of(work) : List.of());
        when(deployment.siteUrl(work.getDeployKey())).thenReturn("https://sites.example.test/" + work.getDeployKey() + "/");
        return work;
    }

    private MockHttpSession session(boolean admin) {
        User user = new User();
        user.setId(admin ? 42L : 84L);
        user.setUserRole((admin ? UserRoleEnum.ADMIN : UserRoleEnum.USER).getValue());
        when(users.selectOneById(user.getId())).thenReturn(user);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, user.getId());
        return session;
    }

    private App app() {
        App app = new App();
        app.setId(9007199254740993L);
        app.setUserId(84L);
        app.setAppName("摄影作品集");
        app.setCodeGenType("multi_file");
        app.setInitPrompt("只有创建者能读取的需求");
        app.setFeatured(true);
        app.setPublishedVersion(101L);
        app.setDeployKey("a".repeat(32));
        return app;
    }
}
