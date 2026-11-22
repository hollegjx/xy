package com.xy.app.controller;

import com.xy.common.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"xy.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:adminSearch;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
class AppAdminSearchTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @MockitoBean UserMapper users;

    @Test
    void searchesStoredMetadataAndKeepsFalseDistinctFromNoFilter() throws Exception {
        db.execute("""
                CREATE TABLE app (id BIGINT PRIMARY KEY, userId BIGINT, appName VARCHAR(128), initPrompt TEXT,
                codeGenType VARCHAR(32), featured BOOLEAN, generatedVersion BIGINT, publishedVersion BIGINT,
                deployKey VARCHAR(64), deployedAt TIMESTAMP, createTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updateTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP, isDelete INT)
                """);
        String id = "9007199254740993";
        db.update("""
                INSERT INTO app (id, userId, appName, codeGenType, featured, deployKey, isDelete) VALUES
                (9007199254740993, 84, '摄影集', 'vue_project', FALSE, 'mountains', 0),
                (55, 84, '摄影集', 'html', TRUE, 'coast', 0), (56, 84, '摄影集', 'vue_project', FALSE, NULL, 1)
                """);
        User admin = new User();
        admin.setId(42L);
        admin.setUserRole(UserRoleEnum.ADMIN.getValue());
        when(users.selectOneById(42L)).thenReturn(admin);

        http.perform(search()).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        http.perform(search().param("id", id).param("appName", "摄影").param("userId", "84")
                        .param("codeGenType", "vue_project").param("deployKey", " mountains ").param("featured", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(id));
        for (String[] mismatch : new String[][]{{"codeGenType", "html"}, {"deployKey", "mount"}, {"featured", "true"}}) {
            http.perform(search().param("id", id).param(mismatch[0], mismatch[1]))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        }
        http.perform(search().param("featured", "false")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].id").value(id));
        http.perform(search().param("codeGenType", "auto")).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder search() {
        var session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return get("/api/apps").contextPath("/api").session(session);
    }
}
