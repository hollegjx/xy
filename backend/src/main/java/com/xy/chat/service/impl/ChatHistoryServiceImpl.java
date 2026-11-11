package com.xy.chat.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.service.AppService;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.chat.model.dto.ChatHistoryQueryRequest;
import com.xy.chat.model.entity.ChatHistory;
import com.xy.chat.model.vo.ChatHistoryPageVO;
import com.xy.chat.model.vo.ChatHistoryVO;
import com.xy.chat.service.ChatHistoryService;
import com.xy.generation.service.impl.GenerationPersistence;
import com.xy.user.model.entity.User;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private final ChatHistoryMapper chatHistoryMapper;
    private final AppService appService;
    private final GenerationPersistence generationPersistence;

    public ChatHistoryServiceImpl(ChatHistoryMapper chatHistoryMapper, AppService appService,
            GenerationPersistence generationPersistence) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.appService = appService;
        this.generationPersistence = generationPersistence;
    }

    @Override
    public ChatHistoryPageVO listMessages(long appId, ChatHistoryQueryRequest request, User loginUser) {
        appService.getAppById(appId, loginUser);
        generationPersistence.recoverInterruptedGenerations();
        List<ChatHistory> messages = chatHistoryMapper.selectListByQuery(
                getQueryWrapper(appId, loginUser.getId(), request));
        boolean hasMore = messages.size() > request.pageSize();
        List<ChatHistoryVO> records = messages.subList(0, Math.min(messages.size(), request.pageSize()))
                .reversed().stream().map(this::getChatHistoryVO).toList();
        return new ChatHistoryPageVO(records, hasMore, hasMore ? records.getFirst().id() : null);
    }

    private QueryWrapper getQueryWrapper(long appId, long userId, ChatHistoryQueryRequest request) {
        return QueryWrapper.create()
                .eq(ChatHistory::getAppId, appId)
                .eq(ChatHistory::getUserId, userId)
                .lt(ChatHistory::getId, request.beforeId(), request.beforeId() != null)
                .orderBy(ChatHistory::getId, false)
                .limit(request.pageSize() + 1);
    }

    private ChatHistoryVO getChatHistoryVO(ChatHistory message) {
        return new ChatHistoryVO(Long.toString(message.getId()), message.getMessageRole(), message.getContent(),
                message.getStatus(), message.getErrorMessage(), message.getCreateTime(), message.getUpdateTime());
    }
}
