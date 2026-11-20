package com.xy.app.controller;

import com.mybatisflex.core.query.CPI;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.entity.App;
import com.xy.config.DeploymentConfiguration;
import com.xy.user.mapper.UserMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
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
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class AppFeaturedControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    AppMapper apps;
    @MockitoBean
    UserMapper users;
    @MockitoBean
    DeploymentConfiguration deployment;

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
