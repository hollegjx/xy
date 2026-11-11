package com.xy.generation.service.impl;

import com.xy.app.mapper.AppMapper;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.exception.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationPersistence {

    private final ChatHistoryMapper chatHistoryMapper;
    private final AppMapper appMapper;
    private boolean interruptedGenerationsRecovered;
    private final Map<Long, PendingFailure> pendingFailures = new LinkedHashMap<>();

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
        // 每次入口请求都补写积压的失败；写入异常时保留当前项，并向调用方返回错误。
        var iterator = pendingFailures.entrySet().iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next();
            saveFailure(pending.getKey(), pending.getValue());
            iterator.remove();
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

    public synchronized void fail(long generationId, String content, String message) {
        PendingFailure failure = new PendingFailure(content, message);
        // 先保存补写所需数据；即使 Session 释放，暂时的数据库故障也不会丢失恢复路径。
        pendingFailures.put(generationId, failure);
        saveFailure(generationId, failure);
        pendingFailures.remove(generationId);
    }

    public void cancel(long generationId, String content) {
        checkSaved(chatHistoryMapper.finishGeneration(generationId, "cancelled", content, null));
    }

    private void saveFailure(long generationId, PendingFailure failure) {
        int rows = chatHistoryMapper.finishGeneration(generationId, "failed", failure.content(), failure.message());
        if (rows == 0) {
            // 写入可能已提交但响应丢失；确认已有终态或已删除后结束补写，绝不覆盖其他终态。
            ChatHistory stored = chatHistoryMapper.selectOneById(generationId);
            if (stored == null || "completed".equals(stored.getStatus()) || "failed".equals(stored.getStatus())
                    || "cancelled".equals(stored.getStatus())) {
                return;
            }
        }
        checkSaved(rows);
    }

    private record PendingFailure(String content, String message) {
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
