package com.xy.user.controller;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.user.constant.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class UserLogoutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserMapper userMapper;

    @Test
    void invalidatesLoginSessionAndRequiresLoginAgain() throws Exception {
        User user = existingUser();
        user.setUserPassword(passwordEncoder.encode("Demo123456!"));
        when(userMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(user);
        when(userMapper.selectOneById(42L)).thenReturn(user);
        MockHttpSession session = login();

        mockMvc.perform(get("/api/users/me").contextPath("/api").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("42"));

        MvcResult logout = mockMvc.perform(post("/api/users/logout").contextPath("/api").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true))
                .andExpect(jsonPath("$.message").value("ok"))
                .andReturn();

        assertThat(session.isInvalid()).isTrue();
        assertThat(logout.getRequest().getSession(false)).isNull();
        mockMvc.perform(get("/api/users/me").contextPath("/api").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(post("/api/users/logout").contextPath("/api").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        MockHttpSession newSession = login();
        assertThat(newSession.getId()).isNotEqualTo(session.getId());
        mockMvc.perform(get("/api/users/me").contextPath("/api").session(newSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("42"));
    }

    @Test
    void rejectsLogoutWithoutSessionAndDoesNotCreateOne() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users/logout").contextPath("/api"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("请先登录"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        verifyNoInteractions(userMapper);
    }

    @Test
    void rejectsLogoutWithoutLoginIdentity() throws Exception {
        mockMvc.perform(post("/api/users/logout").contextPath("/api").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("请先登录"));

        verifyNoInteractions(userMapper);
    }

    @Test
    void rejectsUnavailableAccountAndClearsLoginIdentity() throws Exception {
        MockHttpSession session = loggedInSession();
        when(userMapper.selectOneById(42L)).thenReturn(null);

        mockMvc.perform(post("/api/users/logout").contextPath("/api").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("请先登录"));

        assertThat(session.getAttribute(UserConstant.LOGIN_USER_ID)).isNull();
    }

    @Test
    void keepsOtherSessionForTheSameAccountLoggedIn() throws Exception {
        when(userMapper.selectOneById(42L)).thenReturn(existingUser());
        MockHttpSession currentSession = loggedInSession();
        MockHttpSession otherSession = loggedInSession();

        mockMvc.perform(post("/api/users/logout").contextPath("/api").session(currentSession))
                .andExpect(status().isOk());

        assertThat(currentSession.isInvalid()).isTrue();
        assertThat(otherSession.isInvalid()).isFalse();
        mockMvc.perform(get("/api/users/me").contextPath("/api").session(otherSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("42"));
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userAccount":"xy_demo","userPassword":"Demo123456!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private MockHttpSession loggedInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }

    private User existingUser() {
        User user = new User();
        user.setId(42L);
        user.setUserAccount("xy_demo");
        user.setUserName("演示用户");
        user.setUserRole("user");
        return user;
    }
}
