package com.xy.config;

import com.xy.client.UserIdentityLookup;
import com.xy.common.SessionUserResolver;
import com.xy.user.model.dto.UserIdentity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UserIdentityConfiguration {
    @Bean
    SessionUserResolver<UserIdentity> applicationUserSession(UserIdentityLookup users) {
        return new SessionUserResolver<>(users::findById);
    }
}
