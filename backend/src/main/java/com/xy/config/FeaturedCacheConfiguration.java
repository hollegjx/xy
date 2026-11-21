package com.xy.config;

import com.xy.app.model.vo.AppFeaturedSlice;
import java.time.Duration;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class FeaturedCacheConfiguration implements CachingConfigurer {

    public static final String PAGES = "xy:featured:pages";
    public static final String REVISION = "xy:featured:revision";

    @Bean
    RedisCacheManagerBuilderCustomizer featuredPagesInRedis() {
        var base = RedisCacheConfiguration.defaultCacheConfig().disableCachingNullValues();
        var pages = base.entryTtl(Duration.ofMinutes(5)).serializeValuesWith(
                SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(AppFeaturedSlice.class)));
        var revision = base.serializeValuesWith(SerializationPair.fromSerializer(StringRedisSerializer.UTF_8));
        return builder -> builder.withCacheConfiguration(PAGES, pages).withCacheConfiguration(REVISION, revision);
    }

    @Override
    public CacheErrorHandler errorHandler() {
        // Redis 暂不可用时，读请求查库，已落库的业务写入也不应被报告为失败。
        return new LoggingCacheErrorHandler(false);
    }
}
