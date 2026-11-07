package com.xy.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("数据库连接失败，请检查本地配置"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsAConnectionWithoutASelectedDatabase() throws Exception {
        when(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).thenReturn(null);

        mockMvc.perform(get("/api/health").contextPath("/api"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50000));
    }

    @Test
    void exposesTheHealthEndpointInOpenApi() throws Exception {
        mockMvc.perform(get("/api/v3/api-docs").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/health'].get.summary").value("检查后端与数据库连接"));
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
