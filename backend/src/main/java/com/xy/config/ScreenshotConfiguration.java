package com.xy.config;

import com.xy.client.ScreenshotService;
import com.xy.screenshot.DockerScreenshotRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "xy.cover.enabled", havingValue = "true")
public class ScreenshotConfiguration {
    @Bean
    ScreenshotService screenshotService(@Value("${xy.vue-build.docker-executable:docker}") String docker) {
        return new DockerScreenshotRenderer(docker);
    }
}
