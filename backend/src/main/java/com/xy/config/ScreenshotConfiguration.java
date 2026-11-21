package com.xy.config;

import com.xy.client.ScreenshotService;
import com.xy.client.dubbo.DubboScreenshotClient;
import com.xy.screenshot.DockerScreenshotRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!nacos")
@ConditionalOnProperty(name = "xy.cover.enabled", havingValue = "true")
public class ScreenshotConfiguration {
    @Bean
    ScreenshotService screenshotService(@Value("${xy.cover.rpc-url:}") String rpcUrl,
            @Value("${xy.vue-build.docker-executable:docker}") String docker) {
        return rpcUrl.isBlank() ? new DockerScreenshotRenderer(docker) : new DubboScreenshotClient(rpcUrl);
    }
}
