package com.xy.chat.service;

import com.xy.chat.model.dto.ChatHistoryQueryRequest;
import com.xy.chat.model.vo.ChatHistoryPageVO;
import com.xy.user.model.entity.User;

public interface ChatHistoryService {

    ChatHistoryPageVO listMessages(long appId, ChatHistoryQueryRequest request, User loginUser);
}
