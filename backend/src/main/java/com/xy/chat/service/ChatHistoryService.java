package com.xy.chat.service;

import com.xy.chat.model.dto.ChatHistoryQueryRequest;
import com.xy.chat.model.vo.ChatHistoryPageVO;
import com.xy.user.model.UserActor;

public interface ChatHistoryService {

    ChatHistoryPageVO listMessages(long appId, ChatHistoryQueryRequest request, UserActor loginUser);
}
