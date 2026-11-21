package com.xy.generation.service.impl;

import com.xy.generation.GenerationTestRedis;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GenerationMemoryRedisTest {

    @Test
    void sharedWindowIsScopedToOwnerAppAndCommittedVersionAndCanRecover(@TempDir Path dir) throws Exception {
        var redis = GenerationTestRedis.start(dir);
        try {
            var sql = mock(GenerationPersistence.class);
            when(sql.loadSuccessfulPrompts(55, 7, 101)).thenReturn(List.of("建立画廊", "放大标题"));
            when(sql.loadSuccessfulPrompts(56, 8, 110)).thenReturn(List.of("独立应用"));
            when(sql.loadSuccessfulPrompts(55, 7, 120)).thenReturn(List.of("新版本"));
            var contexts = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                    .withUserConfiguration(RedisGenerationMemoryStore.class, GenerationMemory.class)
                    .withBean(GenerationPersistence.class, () -> sql)
                    .withPropertyValues("spring.data.redis.port=" + redis.ports().getFirst(), "spring.data.redis.database=2");
            contexts.run(first -> first.getBean(GenerationMemory.class).afterCommit(new GenerationMemory.Committed(55, 7, 101)));
            contexts.run(restarted -> {
                var memory = restarted.getBean(GenerationMemory.class);
                var store = restarted.getBean(StringRedisTemplate.class);
                assertThat(memory.prompts(55, 7, 101)).containsExactly("建立画廊", "放大标题");
                verify(sql).loadSuccessfulPrompts(55, 7, 101);
                assertThat(memory.prompts(56, 8, 110)).containsExactly("独立应用");
                assertThat(memory.prompts(55, 7, 120)).containsExactly("新版本");
                String key = "xy:chat:memory:7:55:101";
                assertThat(store.getExpire(key)).isBetween(3590L, 3600L);
                store.expireAt(key, Instant.EPOCH);
                assertThat(memory.prompts(55, 7, 101)).containsExactly("建立画廊", "放大标题");
                verify(sql, times(2)).loadSuccessfulPrompts(55, 7, 101);
            });
        } finally {
            redis.stop();
        }
    }
}
