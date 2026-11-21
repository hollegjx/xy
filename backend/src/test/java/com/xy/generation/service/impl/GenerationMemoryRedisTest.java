package com.xy.generation.service.impl;

import com.xy.generation.GenerationTestRedis;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
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
            var gallery = List.<ChatMessage>of(UserMessage.from("建立画廊"), AiMessage.from("首页是网格布局"),
                    UserMessage.from("放大标题"), AiMessage.from("已将标题改为两行"));
            var other = List.<ChatMessage>of(UserMessage.from("独立应用"), AiMessage.from("另一个用户的作品"));
            var later = List.<ChatMessage>of(UserMessage.from("新版本"), AiMessage.from("新的回复"));
            when(sql.loadSuccessfulMessages(55, 7, 101)).thenReturn(gallery);
            when(sql.loadSuccessfulMessages(56, 8, 110)).thenReturn(other);
            when(sql.loadSuccessfulMessages(55, 7, 120)).thenReturn(later);
            var contexts = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                    .withUserConfiguration(RedisGenerationMemoryStore.class, GenerationMemory.class)
                    .withBean(GenerationPersistence.class, () -> sql)
                    .withPropertyValues("spring.data.redis.port=" + redis.ports().getFirst(), "spring.data.redis.database=2");
            contexts.run(first -> {
                var store = first.getBean(StringRedisTemplate.class);
                store.opsForValue().set("xy:chat:memory:7:55:101",
                        ChatMessageSerializer.messagesToJson(List.of(UserMessage.from("只有需求的旧缓存"))));
                assertThat(first.getBean(GenerationMemory.class).messages(55, 7, 101)).containsExactlyElementsOf(gallery);
                first.getBean(GenerationMemory.class).afterCommit(new GenerationMemory.Committed(55, 7, 120));
            });
            contexts.run(restarted -> {
                var memory = restarted.getBean(GenerationMemory.class);
                var store = restarted.getBean(StringRedisTemplate.class);
                assertThat(memory.messages(55, 7, 101)).containsExactlyElementsOf(gallery);
                verify(sql).loadSuccessfulMessages(55, 7, 101);
                assertThat(memory.messages(56, 8, 110)).containsExactlyElementsOf(other);
                assertThat(memory.messages(55, 7, 120)).containsExactlyElementsOf(later);
                verify(sql).loadSuccessfulMessages(55, 7, 120);
                String key = "xy:chat:memory:roles:7:55:101";
                assertThat(store.getExpire(key)).isBetween(3590L, 3600L);
                store.expireAt(key, Instant.EPOCH);
                assertThat(memory.messages(55, 7, 101)).containsExactlyElementsOf(gallery);
                verify(sql, times(2)).loadSuccessfulMessages(55, 7, 101);
            });
        } finally {
            redis.stop();
        }
    }
}
