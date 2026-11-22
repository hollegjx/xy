package com.xy.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.client.UserIdentityLookup;
import com.xy.common.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserIdentity;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"xy.user.http-enabled=false", "server.servlet.context-path=/api",
        "spring.datasource.url=jdbc:mysql://unused.invalid:3306/xy_test"})
@AutoConfigureMockMvc
@ActiveProfiles("gateway-docs")
class PlatformUserHttpCutoverTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @MockitoBean UserIdentityLookup identities;
    @MockitoBean UserMapper users;

    @Test
    void offersBothLiveDocumentsThroughTheGatewayOrigin() throws Exception {
        http.perform(get("/api/v3/api-docs/swagger-config").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls.length()").value(2))
                .andExpect(jsonPath("$.urls[?(@.name == 'xy-app')].url").value(contains("/api/v3/api-docs")))
                .andExpect(jsonPath("$.urls[?(@.name == 'xy-user')].url").value(contains("/api/v3/api-docs/xy-user")))
                .andExpect(jsonPath("$['urls.primaryName']").value("xy-app"));
    }

    @Test
    void onlyDeletionStaysOnPlatformAndChecksTheRoleBeforeTouchingTheTarget() throws Exception {
        var result = http.perform(get("/api/v3/api-docs").contextPath("/api")).andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("/api")).andReturn();
        var paths = json.readTree(result.getResponse().getContentAsByteArray()).path("paths");
        var accountOperations = new HashSet<String>();
        paths.fields().forEachRemaining(path -> {
            if (path.getKey().startsWith("/users")) {
                path.getValue().fieldNames().forEachRemaining(method -> accountOperations.add(method + " " + path.getKey()));
            }
        });
        assertThat(accountOperations).isEqualTo(Set.of("delete /users/{id}"));
        when(identities.findById(42L)).thenReturn(new UserIdentity(42L, "user"));
        http.perform(delete("/api/users/84").contextPath("/api").sessionAttr(UserConstant.LOGIN_USER_ID, 42L))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        verify(identities).findById(42L);
        verifyNoInteractions(users);
    }
}
