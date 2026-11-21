package com.xy.generation.service.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
final class GenerationMemory {
    record Committed(long appId, long userId, long versionId) {}
    private static final Logger log = LoggerFactory.getLogger(GenerationMemory.class);
    private final ChatMemoryStore store;
    private final GenerationPersistence history;

    GenerationMemory(ChatMemoryStore store, GenerationPersistence history) {
        this.store = store;
        this.history = history;
    }

    List<String> prompts(long appId, long userId, long versionId) {
        var version = new Committed(appId, userId, versionId);
        var window = window(version);
        try {
            var messages = window.messages();
            if (!messages.isEmpty()) return messages.stream().map(m -> ((UserMessage) m).singleText()).toList();
        } catch (RuntimeException unavailable) {
            // 记忆只是可重建的投影；数据库和成功源码仍是权威记录。
            log.debug("无法读取生成记忆，使用数据库恢复：{}", unavailable.getClass().getSimpleName());
        }
        return restore(version);
    }

    @TransactionalEventListener
    void afterCommit(Committed version) {
        try {
            restore(version);
        } catch (RuntimeException unavailable) {
            log.warn("成功版本 {} 的记忆暂未预热，将在续改时恢复", version.versionId());
        }
    }

    private List<String> restore(Committed version) {
        List<String> prompts = history.loadSuccessfulPrompts(version.appId(), version.userId(), version.versionId());
        try {
            window(version).set(prompts.stream().<ChatMessage>map(UserMessage::from).toList());
        } catch (RuntimeException unavailable) {
            log.debug("无法写入生成记忆：{}", unavailable.getClass().getSimpleName());
        }
        return prompts;
    }

    private MessageWindowChatMemory window(Committed v) {
        return MessageWindowChatMemory.builder().id(v.userId() + ":" + v.appId() + ":" + v.versionId())
                .maxMessages(10).chatMemoryStore(store).build();
    }
}
