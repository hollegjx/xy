package com.xy.config;

import com.xy.client.UserIdentityLookup;
import com.xy.common.SessionUserResolver;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserIdentity;
import com.xy.user.service.impl.DatabaseUserIdentityLookup;
import com.xy.user.service.impl.RemoteUserIdentityLookup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!nacos")
public class UserIdentityConfiguration {
    @Bean
    UserIdentityLookup applicationUserIdentityLookup(@Value("${xy.user.rpc-url:}") String rpcUrl,
            ObjectProvider<UserMapper> users) {
        return rpcUrl.isBlank() ? new DatabaseUserIdentityLookup(users.getObject()) : new RemoteUserIdentityLookup(rpcUrl);
    }

    @Bean
    SessionUserResolver<UserIdentity> applicationUserSession(UserIdentityLookup users) {
        return new SessionUserResolver<>(users::findById);
    }
}
