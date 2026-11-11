package com.xy.generation.service;

import com.xy.app.mapper.AppMapper;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationPersistence {

    private final ChatHistoryMapper chatHistoryMapper;
    private final AppMapper appMapper;
    private boolean interruptedGenerationsRecovered;

    public GenerationPersistence(ChatHistoryMapper chatHistoryMapper, AppMapper appMapper) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.appMapper = appMapper;
    }

    // 单实例首次生成/查询共用这道入口。恢复独立提交后才允许新任务写入，避免误伤本进程任务。
    public synchronized void recoverInterruptedGenerations() {
        if (!interruptedGenerationsRecovered) {
            chatHistoryMapper.failInterruptedGenerations();
            interruptedGenerationsRecovered = true;
        }
    }

    @Transactional
    public long start(long appId, long userId, String prompt) {
        saveMessage(appId, userId, "user", prompt, "completed");
        return saveMessage(appId, userId, "assistant", "", "running");
    }

    @Transactional
    public void complete(long appId, long userId, long generationId, String content) {
        checkSaved(chatHistoryMapper.finishGeneration(generationId, "completed", content, null));
        checkSaved(appMapper.updateGeneratedVersion(appId, userId, generationId));
    }

    public void fail(long generationId, String content, String message) {
        checkSaved(chatHistoryMapper.finishGeneration(generationId, "failed", content, message));
    }

    private long saveMessage(long appId, long userId, String role, String content, String status) {
        ChatHistory message = new ChatHistory();
        message.setAppId(appId);
        message.setUserId(userId);
        message.setMessageRole(role);
        message.setContent(content);
        message.setStatus(status);
        checkSaved(chatHistoryMapper.insert(message));
        return message.getId();
    }

    private void checkSaved(int rows) {
        if (rows != 1) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "保存生成记录失败");
        }
    }
}
