package com.xy.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.common.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserLoginRequest;
import com.xy.user.model.entity.User;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class UserLoginControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserMapper userMapper;

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void logsInWithDatabaseRoleAndStoresOnlyUserId(String role) throws Exception {
        when(userMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(existingUser(role));

        MvcResult result = mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("xy_demo", "Demo123456!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", aMapWithSize(4)))
                .andExpect(jsonPath("$.data.id").value("42"))
                .andExpect(jsonPath("$.data.userAccount").value("xy_demo"))
                .andExpect(jsonPath("$.data.userName").value("演示用户"))
                .andExpect(jsonPath("$.data.userRole").value(role))
                .andExpect(jsonPath("$.data.userPassword").doesNotExist())
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(UserConstant.LOGIN_USER_ID)).isEqualTo(42L);
        assertThat(Collections.list(session.getAttributeNames())).containsExactly(UserConstant.LOGIN_USER_ID);
    }

    @Test
    void renewsExistingSessionIdAfterSuccessfulLogin() throws Exception {
        when(userMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(existingUser("user"));
        MockHttpSession session = new MockHttpSession();
        String previousId = session.getId();

        mockMvc.perform(post("/api/users/login").contextPath("/api").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("xy_demo", "Demo123456!")))
                .andExpect(status().isOk());

        assertThat(session.getId()).isNotEqualTo(previousId);
        assertThat(session.getAttribute(UserConstant.LOGIN_USER_ID)).isEqualTo(42L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUnknownAccountAndWrongPasswordWithoutCreatingSession(boolean accountExists) throws Exception {
        when(userMapper.selectOneByQuery(any(QueryWrapper.class)))
                .thenReturn(accountExists ? existingUser("user") : null);

        MvcResult result = mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("xy_demo", "incorrect")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("账号或密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidLogins")
    void rejectsMissingLoginFieldsBeforeQuery(String account, String password, String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(account, password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(message))
                .andReturn();

        verifyNoInteractions(userMapper);
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    static Stream<Arguments> invalidLogins() {
        return Stream.of(
                Arguments.of(null, "demo", "账号不能为空"),
                Arguments.of(" ", "demo", "账号不能为空"),
                Arguments.of("xy_demo", null, "密码不能为空"),
                Arguments.of("xy_demo", " ", "密码不能为空"));
    }

    @Test
    void preservesMethodNotAllowedStatusAndAllowedMethods() throws Exception {
        mockMvc.perform(get("/api/users/login").contextPath("/api"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")))
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请求方法不支持"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(userMapper);
    }

    @Test
    void preservesUnsupportedMediaTypeStatusAndAcceptedTypes() throws Exception {
        mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.TEXT_PLAIN).content(loginBody("xy_demo", "demo")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请求内容类型不支持"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(userMapper);
    }

    @Test
    void keepsUnexpectedApplicationErrorsAsInternalServerErrors() throws Exception {
        when(userMapper.selectOneByQuery(any(QueryWrapper.class)))
                .thenThrow(new IllegalStateException("internal implementation details"));

        mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("xy_demo", "demo")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private String loginBody(String account, String password) throws Exception {
        return objectMapper.writeValueAsString(new UserLoginRequest(account, password));
    }

    private User existingUser(String role) {
        User user = new User();
        user.setId(42L);
        user.setUserAccount("xy_demo");
        user.setUserName("演示用户");
        user.setUserRole(role);
        user.setUserPassword(passwordEncoder.encode("Demo123456!"));
        return user;
    }
}
