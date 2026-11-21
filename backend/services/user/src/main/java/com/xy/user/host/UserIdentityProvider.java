package com.xy.user.host;

import com.xy.client.UserIdentityLookup;
import com.xy.user.model.dto.UserIdentity;
import com.xy.user.service.impl.DatabaseUserIdentityLookup;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService(interfaceClass = UserIdentityLookup.class, retries = 0, timeout = 3000)
public class UserIdentityProvider implements UserIdentityLookup {
    private final DatabaseUserIdentityLookup users;

    public UserIdentityProvider(DatabaseUserIdentityLookup users) {
        this.users = users;
    }

    @Override
    public UserIdentity findById(long accountId) {
        return users.findById(accountId);
    }
}
