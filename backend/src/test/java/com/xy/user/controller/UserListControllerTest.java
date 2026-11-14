package com.xy.user.controller;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xy.common.UserConstant;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import com.xy.user.model.enums.UserRoleEnum;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
class UserListControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    UserMapper users;

    @Test
    void letsAdminsFilterAndPageUsersWithoutReturningPasswords() throws Exception {
        when(users.selectOneById(42L)).thenReturn(user(42L, UserRoleEnum.ADMIN));
        User record = user(84L, UserRoleEnum.USER);
        when(users.paginate(eq(2), eq(1), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(record), 2, 1, 3));

        mockMvc.perform(query().session(session()).param("userAccount", " xy_demo ")
                        .param("current", "2").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.data.records[0].id").value("84"))
                .andExpect(jsonPath("$.data.records[0].userAccount").value("xy_demo"))
                .andExpect(jsonPath("$.data.records[0].userName").value("演示用户"))
                .andExpect(jsonPath("$.data.records[0].userRole").value("user"))
                .andExpect(jsonPath("$.data.records[0].userPassword").doesNotExist());

        ArgumentCaptor<QueryWrapper> filter = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(users).paginate(eq(2), eq(1), filter.capture());
        assertThat(filter.getValue().toSQL()).contains("`userAccount` LIKE '%xy_demo%'",
                "`createTime` DESC", "`id` DESC");
    }

    @Test
    void defaultsToTheFirstPageAndTreatsBlankAccountAsNoFilter() throws Exception {
        when(users.selectOneById(42L)).thenReturn(user(42L, UserRoleEnum.ADMIN));
        when(users.paginate(eq(1), eq(10), any(QueryWrapper.class)))
                .thenReturn(new Page<>(List.of(), 1, 10, 0));

        mockMvc.perform(query().session(session()).param("userAccount", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));

        ArgumentCaptor<QueryWrapper> filter = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(users).paginate(eq(1), eq(10), filter.capture());
        assertThat(filter.getValue().toSQL()).doesNotContain("LIKE");
    }

    @Test
    void rejectsAnonymousRequestsBeforeReadingUsers() throws Exception {
        mockMvc.perform(query())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsOrdinaryUsersBasedOnTheDatabaseRole() throws Exception {
        when(users.selectOneById(42L)).thenReturn(user(42L, UserRoleEnum.USER));

        mockMvc.perform(query().session(session()).param("userRole", "admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        verify(users).selectOneById(42L);
        verify(users, never()).paginate(any(Number.class), any(Number.class), any(QueryWrapper.class));
    }

    @Test
    void rejectsInvalidPaginationBeforeReadingUsers() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(query().param("current", "0"),
                query().param("pageSize", "0"))) {
            mockMvc.perform(request.session(session()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        verifyNoInteractions(users);
    }

    private MockHttpServletRequestBuilder query() {
        return get("/api/users").contextPath("/api");
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }

    private User user(long id, UserRoleEnum role) {
        User user = new User();
        user.setId(id);
        user.setUserAccount("xy_demo");
        user.setUserName("演示用户");
        user.setUserRole(role.getValue());
        user.setUserPassword("test-password-hash");
        return user;
    }
}
