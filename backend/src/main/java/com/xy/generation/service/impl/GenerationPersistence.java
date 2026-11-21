package com.xy.generation.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.chat.model.enums.MessageRoleEnum;
import com.xy.chat.model.enums.MessageStatusEnum;
import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationPersistence {

    private final ChatHistoryMapper chatHistoryMapper;
    private final AppMapper appMapper;
    private final ApplicationEventPublisher events;
    private boolean interruptedGenerationsRecovered;
    private final Map<Long, PendingFailure> pendingFailures = new LinkedHashMap<>();

    public GenerationPersistence(ChatHistoryMapper chatHistoryMapper, AppMapper appMapper, ApplicationEventPublisher events) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.appMapper = appMapper;
        this.events = events;
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

    @LimitGenerationStarts(permits = 5, seconds = 60)
    @Transactional
    public long start(long appId, long userId, String prompt) {
        saveMessage(appId, userId, MessageRoleEnum.USER, prompt, MessageStatusEnum.COMPLETED);
        return saveMessage(appId, userId, MessageRoleEnum.ASSISTANT, "", MessageStatusEnum.RUNNING);
    }

    public List<String> loadSuccessfulPrompts(long appId, long userId, long versionId) {
        List<ChatHistory> recent = chatHistoryMapper.selectListByQuery(QueryWrapper.create()
                .eq(ChatHistory::getAppId, appId)
                .eq(ChatHistory::getUserId, userId)
                .le(ChatHistory::getId, versionId)
                .orderBy(ChatHistory::getId, false)
                .limit(20));
        if (recent.isEmpty() || recent.getFirst().getId() != versionId
                || !MessageRoleEnum.ASSISTANT.getValue().equals(recent.getFirst().getMessageRole())
                || !MessageStatusEnum.COMPLETED.getValue().equals(recent.getFirst().getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用成功版本记录不可用");
        }
        List<String> prompts = new ArrayList<>();
        // 同一应用串行生成，每轮事务内先存用户消息再存 AI 消息；不假定自增 ID 连续。
        for (int i = recent.size() - 1; i > 0; i--) {
            ChatHistory request = recent.get(i);
            ChatHistory reply = recent.get(i - 1);
            if (MessageRoleEnum.USER.getValue().equals(request.getMessageRole())
                    && MessageStatusEnum.COMPLETED.getValue().equals(request.getStatus())
                    && MessageRoleEnum.ASSISTANT.getValue().equals(reply.getMessageRole())
                    && MessageStatusEnum.COMPLETED.getValue().equals(reply.getStatus())) {
                prompts.add(request.getContent());
            }
        }
        return prompts;
    }

    @Transactional
    public void complete(long appId, long userId, long generationId, String content) {
        checkSaved(chatHistoryMapper.finishGeneration(generationId, MessageStatusEnum.COMPLETED.getValue(), content, null));
        checkSaved(appMapper.updateGeneratedVersion(appId, userId, generationId));
        events.publishEvent(new GenerationMemory.Committed(appId, userId, generationId));
    }

    public synchronized void fail(long generationId, String content, String message) {
        PendingFailure failure = new PendingFailure(content, message);
        // 先保存补写所需数据；即使 Session 释放，暂时的数据库故障也不会丢失恢复路径。
        pendingFailures.put(generationId, failure);
        saveFailure(generationId, failure);
        pendingFailures.remove(generationId);
    }

    public void cancel(long generationId, String content) {
        checkSaved(chatHistoryMapper.finishGeneration(generationId, MessageStatusEnum.CANCELLED.getValue(), content, null));
    }

    private void saveFailure(long generationId, PendingFailure failure) {
        int rows = chatHistoryMapper.finishGeneration(generationId, MessageStatusEnum.FAILED.getValue(),
                failure.content(), failure.message());
        if (rows == 0) {
            // 写入可能已提交但响应丢失；确认已有终态或已删除后结束补写，绝不覆盖其他终态。
            ChatHistory stored = chatHistoryMapper.selectOneById(generationId);
            if (stored == null || MessageStatusEnum.COMPLETED.getValue().equals(stored.getStatus())
                    || MessageStatusEnum.FAILED.getValue().equals(stored.getStatus())
                    || MessageStatusEnum.CANCELLED.getValue().equals(stored.getStatus())) {
                return;
            }
        }
        checkSaved(rows);
    }

    private record PendingFailure(String content, String message) {
    }

    private long saveMessage(long appId, long userId, MessageRoleEnum role, String content, MessageStatusEnum status) {
        ChatHistory message = new ChatHistory();
        message.setAppId(appId);
        message.setUserId(userId);
        message.setMessageRole(role.getValue());
        message.setContent(content);
        message.setStatus(status.getValue());
        checkSaved(chatHistoryMapper.insert(message));
        return message.getId();
    }

    private void checkSaved(int rows) {
        if (rows != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存生成记录失败");
        }
    }
}
