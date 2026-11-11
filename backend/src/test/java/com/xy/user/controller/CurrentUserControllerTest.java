package com.xy.user.controller;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.common.UserConstant;
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
import static org.hamcrest.Matchers.aMapWithSize;
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
class CurrentUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserMapper userMapper;

    @Test
    void returnsLatestUserInformationUsingTheLoginSession() throws Exception {
        User loginUser = user(42L, "xy_demo", "原昵称", "user");
        loginUser.setUserPassword(passwordEncoder.encode("Demo123456!"));
        when(userMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(loginUser);
        MvcResult login = mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userAccount":"xy_demo","userPassword":"Demo123456!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        String sessionId = session.getId();
        when(userMapper.selectOneById(42L)).thenReturn(user(42L, "xy_demo", "最新昵称", "admin"));

        mockMvc.perform(get("/api/users/me").contextPath("/api").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", aMapWithSize(4)))
                .andExpect(jsonPath("$.data.id").value("42"))
                .andExpect(jsonPath("$.data.userAccount").value("xy_demo"))
                .andExpect(jsonPath("$.data.userName").value("最新昵称"))
                .andExpect(jsonPath("$.data.userRole").value("admin"))
                .andExpect(jsonPath("$.data.userPassword").doesNotExist());
        assertThat(session.getId()).isEqualTo(sessionId);
    }

    @Test
    void rejectsRequestsWithoutSessionAndDoesNotCreateOne() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me").contextPath("/api"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("请先登录"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        verifyNoInteractions(userMapper);
    }

    @Test
    void rejectsSessionsWithoutLoginIdentity() throws Exception {
        mockMvc.perform(get("/api/users/me").contextPath("/api").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("请先登录"));
        verifyNoInteractions(userMapper);
    }

    @Test
    void clearsLoginIdentityWhenAccountIsNoLongerAvailable() throws Exception {
        MockHttpSession session = loggedInSession(42L);
        when(userMapper.selectOneById(42L)).thenReturn(null);

        mockMvc.perform(get("/api/users/me").contextPath("/api").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(session.getAttribute(UserConstant.LOGIN_USER_ID)).isNull();
    }

    @Test
    void isolatesSessionIdentitiesAndIgnoresClientProvidedUserIds() throws Exception {
        when(userMapper.selectOneById(42L)).thenReturn(user(42L, "account_a", "用户 A", "user"));
        when(userMapper.selectOneById(84L)).thenReturn(user(84L, "account_b", "用户 B", "user"));

        mockMvc.perform(get("/api/users/me").contextPath("/api")
                        .session(loggedInSession(42L)).param("userId", "84"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("42"))
                .andExpect(jsonPath("$.data.userAccount").value("account_a"));

        mockMvc.perform(get("/api/users/me").contextPath("/api")
                        .session(loggedInSession(84L)).param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("84"))
                .andExpect(jsonPath("$.data.userAccount").value("account_b"));
    }

    private MockHttpSession loggedInSession(long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, userId);
        return session;
    }

    private User user(long id, String account, String name, String role) {
        User user = new User();
        user.setId(id);
        user.setUserAccount(account);
        user.setUserName(name);
        user.setUserRole(role);
        return user;
    }
}
