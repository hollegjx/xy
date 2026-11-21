package com.xy.user.service.impl;

import com.xy.client.UserIdentityLookup;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserIdentity;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserIdentityLookup implements UserIdentityLookup {
    private final UserMapper users;

    public DatabaseUserIdentityLookup(UserMapper users) {
        this.users = users;
    }

    @Override
    public UserIdentity findById(long accountId) {
        var user = users.selectOneById(accountId);
        return user == null ? null : new UserIdentity(user.getId(), user.getUserRole());
    }
}
