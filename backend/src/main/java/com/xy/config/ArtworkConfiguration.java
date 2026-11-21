package com.xy.config;

import dev.langchain4j.community.model.dashscope.WanxImageModel;
import dev.langchain4j.model.image.ImageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ArtworkConfiguration {
    @Bean
    @ConditionalOnProperty(name = "xy.artwork.logo-provider", havingValue = "dashscope")
    ImageModel logoImageModel(@Value("${xy.artwork.dashscope-api-key}") String key,
            @Value("${xy.artwork.dashscope-model}") String model) {
        return WanxImageModel.builder().modelName(model).apiKey(key).promptExtend(false).build();
    }
}
