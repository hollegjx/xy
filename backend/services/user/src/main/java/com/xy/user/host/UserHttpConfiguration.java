package com.xy.user.host;

import com.xy.config.UserPasswordConfiguration;
import com.xy.exception.GlobalExceptionHandler;
import com.xy.exception.SessionAvailabilityFilter;
import com.xy.user.controller.UserController;
import com.xy.user.service.impl.RoleAuthorizationAspect;
import com.xy.user.service.impl.UserServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import({UserController.class, UserServiceImpl.class, UserPasswordConfiguration.class,
        RoleAuthorizationAspect.class, GlobalExceptionHandler.class, SessionAvailabilityFilter.class})
class UserHttpConfiguration {}
