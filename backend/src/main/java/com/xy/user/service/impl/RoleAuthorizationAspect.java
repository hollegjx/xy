package com.xy.user.service.impl;

import com.xy.common.ErrorCode;
import com.xy.common.RequireRole;
import com.xy.exception.BusinessException;
import com.xy.user.model.entity.User;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RoleAuthorizationAspect {

    @Before("@annotation(required)")
    public void authorize(JoinPoint call, RequireRole required) {
        Object[] args = call.getArgs();
        if (args.length == 0 || !(args[args.length - 1] instanceof User actor)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
        if (!required.value().getValue().equals(actor.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, required.message());
        }
    }
}
