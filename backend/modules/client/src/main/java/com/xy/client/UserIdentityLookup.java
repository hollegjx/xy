package com.xy.client;

import com.xy.user.model.dto.UserIdentity;

/** 内部鉴权查询；只接受账号 ID，不接收 HTTP 请求或 Cookie。账号不存在或已删除时返回 null。 */
public interface UserIdentityLookup {
    UserIdentity findById(long accountId);
}
