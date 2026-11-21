package com.xy.chat.controller;

import com.xy.chat.model.dto.ChatHistoryQueryRequest;
import com.xy.chat.model.vo.ChatHistoryPageVO;
import com.xy.chat.service.ChatHistoryService;
import com.xy.common.ApiResponse;
import com.xy.common.SessionUserResolver;
import com.xy.user.model.dto.UserIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "应用对话")
@RestController
@RequestMapping("/apps/{appId}/messages")
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;
    private final SessionUserResolver<UserIdentity> sessionUsers;

    public ChatHistoryController(ChatHistoryService chatHistoryService, SessionUserResolver<UserIdentity> sessionUsers) {
        this.chatHistoryService = chatHistoryService;
        this.sessionUsers = sessionUsers;
    }

    @Operation(summary = "查询应用消息与生成状态", description = "仅创建者可查询，包括管理员在内的其他账号返回 403。"
            + "默认最近 10 条，每页最多 20 条；用 nextCursor 作为 beforeId 向前翻页，本页按旧到新排列。"
            + "重启后首次查询或生成会将遗留 running 标记为 failed；运行中的增量输出仍通过 SSE 获取。")
    @GetMapping
    public ApiResponse<ChatHistoryPageVO> listMessages(
            @Parameter(description = "应用 ID，必须大于 0", example = "55") @Positive @PathVariable long appId,
            @Valid @ModelAttribute @ParameterObject ChatHistoryQueryRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(chatHistoryService.listMessages(appId, request,
                sessionUsers.require(servletRequest)));
    }
}
