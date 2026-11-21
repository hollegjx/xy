package com.xy.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration(proxyBeanMethods = false)
public class GenerationRedisConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedissonClient generationRedisson(RedisConnectionDetails connection, RedisProperties properties) {
        var endpoint = connection.getStandalone();
        var address = UriComponentsBuilder.newInstance().scheme(endpoint.getSslBundle() == null ? "redis" : "rediss")
                .host(endpoint.getHost()).port(endpoint.getPort()).build().toUriString();
        var config = new Config().setLazyInitialization(true);
        var server = config.useSingleServer().setAddress(address).setDatabase(endpoint.getDatabase())
                .setUsername(connection.getUsername()).setPassword(connection.getPassword())
                .setConnectionMinimumIdleSize(1).setSubscriptionConnectionMinimumIdleSize(1).setRetryAttempts(0);
        if (properties.getConnectTimeout() != null) server.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        if (properties.getTimeout() != null) server.setTimeout(Math.toIntExact(properties.getTimeout().toMillis()));
        return Redisson.create(config);
    }
}
