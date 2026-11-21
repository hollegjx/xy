package com.xy.common;

import com.xy.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.LongFunction;

/** 在 HTTP 请求所在进程读取 Session；只把服务端保存的账号 ID 交给用户查询。 */
public final class SessionUserResolver<T> {
    private final LongFunction<T> lookup;

    public SessionUserResolver(LongFunction<T> lookup) {
        this.lookup = lookup;
    }

    public T require(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(UserConstant.LOGIN_USER_ID) instanceof Long id)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
        T user = lookup.apply(id);
        if (user == null) {
            session.removeAttribute(UserConstant.LOGIN_USER_ID);
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
        return user;
    }
}
