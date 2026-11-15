package com.xy.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.common.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserUpdateRequest;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class UserUpdateControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    UserMapper users;
    private User admin;
    private User member;

    @BeforeEach
    void prepareUsers() {
        admin = new User();
        admin.setId(42L);
        admin.setUserAccount("xy_admin");
        admin.setUserName("管理员");
        admin.setUserRole(UserRoleEnum.ADMIN.getValue());
        when(users.selectOneById(42L)).thenReturn(admin);
        member = new User();
        member.setId(84L);
        member.setUserAccount("xy_member");
        member.setUserPassword("existing-password-hash");
        member.setUserName("原昵称");
        member.setUserRole(UserRoleEnum.USER.getValue());
        when(users.selectOneById(84L)).thenReturn(member);
    }

    @Test
    void updatesOnlyTheNicknameAndReadsItThroughTheListAndCurrentUser() throws Exception {
        stubUpdate(member);
        when(users.paginate(eq(1), eq(10), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(member), 1, 10, 1));

        mockMvc.perform(update(84L).session(session(42L)).content("""
                        {"userName":"星云成员", "userAccount":"another_account",
                         "userPassword":"another_password", "userRole":"admin"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/api/users").contextPath("/api").session(session(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value("84"))
                .andExpect(jsonPath("$.data.records[0].userName").value("星云成员"))
                .andExpect(jsonPath("$.data.records[0].userAccount").value("xy_member"))
                .andExpect(jsonPath("$.data.records[0].userRole").value("user"));
        mockMvc.perform(get("/api/users/me").contextPath("/api").session(session(84L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userName").value("星云成员"));
        mockMvc.perform(update(84L).session(session(42L)).content("{\"userName\":\"星云成员\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(users).updateUserName(84L, "星云成员");
        assertThat(member.getUserPassword()).isEqualTo("existing-password-hash");
    }

    @Test
    void allowsAnAdminToRenameThemselvesAndAnotherAdmin() throws Exception {
        member.setUserRole(UserRoleEnum.ADMIN.getValue());
        for (User target : List.of(admin, member)) {
            stubUpdate(target);
            mockMvc.perform(update(target.getId()).session(session(42L))
                            .content("{\"userName\":\"新管理员昵称\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(true));
            mockMvc.perform(get("/api/users/me").contextPath("/api").session(session(target.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userName").value("新管理员昵称"))
                    .andExpect(jsonPath("$.data.userRole").value("admin"));
        }
    }

    @Test
    void rejectsMissingBlankOrTooLongNicknamesAndNonPositiveIds() throws Exception {
        for (String name : new String[]{null, "   ", "名".repeat(129)}) {
            mockMvc.perform(update(84L).session(session(42L))
                            .content(objectMapper.writeValueAsString(new UserUpdateRequest(name))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        for (long id : new long[]{0, -1}) {
            mockMvc.perform(update(id).session(session(42L)).content("{\"userName\":\"新昵称\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(users);
    }

    @Test
    void rejectsAnonymousAndOrdinaryUsersBeforeAccessingTheTarget() throws Exception {
        mockMvc.perform(update(84L).content("{\"userName\":\"新昵称\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(users);

        admin.setUserRole(UserRoleEnum.USER.getValue());
        mockMvc.perform(update(84L).session(session(42L)).content("{\"userName\":\"新昵称\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        verify(users, never()).selectOneById(84L);
        verify(users, never()).updateUserName(anyLong(), anyString());
    }

    @Test
    void rejectsMissingUsersWithoutWriting() throws Exception {
        when(users.selectOneById(84L)).thenReturn(null);
        mockMvc.perform(update(84L).session(session(42L)).content("{\"userName\":\"新昵称\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        verify(users, never()).updateUserName(anyLong(), anyString());
    }

    private void stubUpdate(User target) {
        when(users.updateUserName(eq(target.getId()), anyString())).thenAnswer(invocation -> {
            target.setUserName(invocation.getArgument(1));
            return 1;
        });
    }

    private MockHttpServletRequestBuilder update(long id) {
        return patch("/api/users/{id}", id).contextPath("/api").contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpSession session(long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, userId);
        return session;
    }
}
