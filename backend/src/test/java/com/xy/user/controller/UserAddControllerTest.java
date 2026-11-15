package com.xy.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.common.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserAddRequest;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class UserAddControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    PasswordEncoder passwordEncoder;
    @MockitoBean
    UserMapper users;

    @BeforeEach
    void prepareAdmin() {
        User admin = new User();
        admin.setId(42L);
        admin.setUserRole(UserRoleEnum.ADMIN.getValue());
        when(users.selectOneById(42L)).thenReturn(admin);
    }

    @Test
    void createsAnOrdinaryUserWhoCanLogInWithoutReplacingTheAdminSession() throws Exception {
        stubInsert();
        MockHttpSession adminSession = session();
        String body = objectMapper.writeValueAsString(Map.of(
                "userAccount", "xy_member", "userPassword", "Demo123456!",
                "userName", "星云成员", "userRole", "admin"));

        mockMvc.perform(post("/api/users").contextPath("/api").session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("84"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).insert(saved.capture());
        assertThat(saved.getValue().getUserAccount()).isEqualTo("xy_member");
        assertThat(saved.getValue().getUserName()).isEqualTo("星云成员");
        assertThat(saved.getValue().getUserRole()).isEqualTo(UserRoleEnum.USER.getValue());
        assertThat(saved.getValue().getUserPassword()).isNotEqualTo("Demo123456!");
        assertThat(passwordEncoder.matches("Demo123456!", saved.getValue().getUserPassword())).isTrue();
        assertThat(adminSession.getAttribute(UserConstant.LOGIN_USER_ID)).isEqualTo(42L);

        when(users.selectOneByQuery(any(QueryWrapper.class))).thenReturn(saved.getValue());
        mockMvc.perform(post("/api/users/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userAccount":"xy_member","userPassword":"Demo123456!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("84"))
                .andExpect(jsonPath("$.data.userRole").value("user"));
    }

    @Test
    void usesTheAccountWhenTheNicknameIsOmittedOrBlank() throws Exception {
        stubInsert();
        for (String name : new String[]{null, " "}) {
            mockMvc.perform(post("/api/users").contextPath("/api").session(session())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(new UserAddRequest("xy_member", "Demo123456!", name))))
                    .andExpect(status().isOk());
        }
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users, times(2)).insert(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(user ->
                assertThat(user.getUserName()).isEqualTo("xy_member"));
    }

    @Test
    void rejectsDuplicateAccounts() throws Exception {
        when(users.insert(any(User.class))).thenThrow(new DuplicateKeyException("test duplicate"));

        mockMvc.perform(post("/api/users").contextPath("/api").session(session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new UserAddRequest("xy_member", "Demo123456!", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("账号已存在"));
    }

    @Test
    void rejectsAnonymousAndOrdinaryUsersBeforeSaving() throws Exception {
        String body = body(new UserAddRequest("xy_member", "Demo123456!", null));
        mockMvc.perform(post("/api/users").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(users);

        User user = new User();
        user.setId(42L);
        user.setUserRole(UserRoleEnum.USER.getValue());
        when(users.selectOneById(42L)).thenReturn(user);
        mockMvc.perform(post("/api/users").contextPath("/api").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        verify(users, never()).insert(any(User.class));
    }

    @Test
    void rejectsMissingRequiredFieldsAndNamesBeyondTheStorageLimit() throws Exception {
        for (UserAddRequest input : List.of(
                new UserAddRequest(" ", "Demo123456!", null),
                new UserAddRequest("xy_member", " ", null),
                new UserAddRequest("a".repeat(129), "Demo123456!", null),
                new UserAddRequest("xy_member", "Demo123456!", "名".repeat(129)))) {
            mockMvc.perform(post("/api/users").contextPath("/api").session(session())
                            .contentType(MediaType.APPLICATION_JSON).content(body(input)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(users);
    }

    private void stubInsert() {
        when(users.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(84L);
            return 1;
        });
    }

    private String body(UserAddRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }
}
