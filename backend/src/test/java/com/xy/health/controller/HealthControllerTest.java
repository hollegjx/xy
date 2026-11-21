package com.xy.health.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.servlet.context-path=/api",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "logging.level.com.xy.exception.GlobalExceptionHandler=OFF"
})
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsDatabaseNameReportedByTheConnection() throws Exception {
        when(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).thenReturn("xy_test");

        mockMvc.perform(get("/api/health").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database").value("xy_test"));
    }

    @Test
    void reportsUnavailableWithoutExposingTheDatabaseError() throws Exception {
        when(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .thenThrow(new DataAccessResourceFailureException("internal database connection details"));

        mockMvc.perform(get("/api/health").contextPath("/api"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50300))
                .andExpect(jsonPath("$.message").value("数据库连接失败，请检查本地配置"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsAConnectionWithoutASelectedDatabase() throws Exception {
        when(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).thenReturn(null);

        mockMvc.perform(get("/api/health").contextPath("/api"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50300));
    }

    @Test
    void exposesTheAvailableEndpointsInOpenApi() throws Exception {
        mockMvc.perform(get("/api/v3/api-docs").contextPath("/api"))
                .andExpect(status().isOk()).andDo(result -> {
                    if (Boolean.getBoolean("xy.openapi.export")) {
                        Path directory = Files.createDirectories(Path.of("target"));
                        Files.write(directory.resolve("xy-openapi.json"), result.getResponse().getContentAsByteArray());
                    }
                })
                .andExpect(jsonPath("$.components.schemas.AppVO.properties.deployment.anyOf[0]['$ref']").value("#/components/schemas/AppDeployVO"))
                .andExpect(jsonPath("$.components.schemas.AppVO.properties.deployment.anyOf[1].type").value("null"))
                .andExpect(jsonPath("$.paths['/health'].get.summary").value("检查后端与数据库连接"))
                .andExpect(jsonPath("$.paths['/apps'].post.summary").value("创建应用"))
                .andExpect(jsonPath("$.paths['/apps/{id}'].get.summary").value("获取应用详情"))
                .andExpect(jsonPath("$.paths['/apps/{appId}/generations'].post.summary").value("流式生成网站"))
                .andExpect(jsonPath("$.paths['/apps/{appId}/generations/{generationId}/cancel'].post.summary").value("停止应用生成"))
                .andExpect(jsonPath("$.paths['/apps/{appId}/messages'].get.summary").value("查询应用消息与生成状态"))
                .andExpect(jsonPath("$.paths['/apps/{appId}/preview'].get.summary").value("预览最近成功版本"))
                .andExpect(jsonPath("$.paths['/users/register'].post.summary").value("注册用户"))
                .andExpect(jsonPath("$.paths['/users/login'].post.summary").value("用户登录"))
                .andExpect(jsonPath("$.paths['/users/logout'].post.summary").value("用户退出"))
                .andExpect(jsonPath("$.paths['/users/me'].get.summary").value("获取当前用户"));
    }

    @Test
    void servesTheKnife4jDocumentationPage() throws Exception {
        mockMvc.perform(get("/api/doc.html").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Knife4j")));

        mockMvc.perform(get("/api/v3/api-docs/swagger-config").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/api/v3/api-docs"));
    }
}
