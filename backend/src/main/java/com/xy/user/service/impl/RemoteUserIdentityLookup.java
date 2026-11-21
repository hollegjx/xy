package com.xy.user.service.impl;

import com.xy.client.UserIdentityLookup;
import com.xy.client.dubbo.DubboDirectReference;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import com.xy.user.model.dto.UserIdentity;

/** 应用端只将账号 ID 发给用户服务。服务失联与账号不存在是两种结果。 */
public final class RemoteUserIdentityLookup implements UserIdentityLookup, AutoCloseable {
    private final DubboDirectReference<UserIdentityLookup> reference;
    private final UserIdentityLookup users;

    public RemoteUserIdentityLookup(String address) {
        reference = new DubboDirectReference<>(UserIdentityLookup.class, address, 3000);
        users = withAvailabilityCheck(id -> reference.service().findById(id));
    }

    @Override
    public UserIdentity findById(long accountId) {
        return users.findById(accountId);
    }

    public static UserIdentityLookup withAvailabilityCheck(UserIdentityLookup remote) {
        return id -> {
            try {
                return remote.findById(id);
            } catch (RuntimeException unavailable) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "用户身份服务暂时不可用，请稍后重试");
            }
        };
    }

    @Override
    public void close() {
        reference.close();
    }
}
