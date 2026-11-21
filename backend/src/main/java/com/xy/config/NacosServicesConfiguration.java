package com.xy.config;

import com.xy.client.ScreenshotService;
import com.xy.client.UserIdentityLookup;
import com.xy.common.SessionUserResolver;
import com.xy.user.model.dto.UserIdentity;
import com.xy.user.service.impl.RemoteUserIdentityLookup;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 实例地址由 Nacos 提供；关闭封面时不订阅截图服务。 */
@Profile("nacos")
@EnableDubbo
@Configuration(proxyBeanMethods = false)
public class NacosServicesConfiguration {
    @Bean
    @DubboReference(providedBy = "xy-user", protocol = "tri", scope = "remote",
            retries = 0, timeout = 3000, check = false, lazy = true)
    ReferenceBean<UserIdentityLookup> discoveredUsers() {
        return new ReferenceBean<>();
    }

    @Bean
    SessionUserResolver<UserIdentity> applicationUserSession(UserIdentityLookup users) {
        return new SessionUserResolver<>(RemoteUserIdentityLookup.withAvailabilityCheck(users)::findById);
    }

    @Bean
    @ConditionalOnProperty(name = "xy.cover.enabled", havingValue = "true")
    @DubboReference(providedBy = "xy-screenshot", protocol = "tri", scope = "remote",
            retries = 0, timeout = 22_000, check = false, lazy = true)
    ReferenceBean<ScreenshotService> discoveredScreenshots() {
        return new ReferenceBean<>();
    }
}
