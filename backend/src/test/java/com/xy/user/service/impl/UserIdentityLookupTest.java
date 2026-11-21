package com.xy.user.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.common.SessionUserResolver;
import com.xy.common.UserConstant;
import com.xy.exception.BusinessException;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserIdentityLookupTest {
    @Test
    void returnsOnlyIdAndCurrentRoleAndRevokesADeletedAccount() {
        var mapper = mock(UserMapper.class);
        var account = new User();
        account.setId(42L);
        account.setUserRole("user");
        account.setUserAccount("private-account");
        account.setUserPassword("private-hash");
        when(mapper.selectOneById(42L)).thenReturn(account);
        var identities = new DatabaseUserIdentityLookup(mapper);
        var sessionUsers = new SessionUserResolver<>(identities::findById);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        request.setParameter("userId", "99");
        request.setParameter("userRole", "admin");

        var json = new ObjectMapper();
        JsonNode identity = json.valueToTree(sessionUsers.require(request));
        assertThat(identity.size()).isEqualTo(2);
        assertThat(identity.path("id").longValue()).isEqualTo(42L);
        assertThat(identity.path("userRole").textValue()).isEqualTo("user");
        account.setUserRole("admin");
        assertThat(sessionUsers.require(request).getUserRole()).isEqualTo("admin");
        when(mapper.selectOneById(42L)).thenReturn(null);
        assertThatThrownBy(() -> sessionUsers.require(request)).isInstanceOf(BusinessException.class);
        assertThat(request.getSession().getAttribute(UserConstant.LOGIN_USER_ID)).isNull();
        verify(mapper, never()).selectOneById(99L);
    }
}
