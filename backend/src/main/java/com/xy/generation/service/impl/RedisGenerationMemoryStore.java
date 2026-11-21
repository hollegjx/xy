package com.xy.generation.service.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
final class RedisGenerationMemoryStore implements ChatMemoryStore {
    private final StringRedisTemplate redis;

    RedisGenerationMemoryStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<ChatMessage> getMessages(Object id) {
        String value = redis.opsForValue().get(key(id));
        return value == null ? List.of() : ChatMessageDeserializer.messagesFromJson(value);
    }

    @Override
    public void updateMessages(Object id, List<ChatMessage> messages) {
        redis.opsForValue().set(key(id), ChatMessageSerializer.messagesToJson(messages), Duration.ofHours(1));
    }

    @Override
    public void deleteMessages(Object id) {
        redis.delete(key(id));
    }

    private String key(Object id) {
        return "xy:chat:memory:" + id;
    }
}
