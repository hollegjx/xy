package com.xy.generation.controller;

import com.xy.generation.model.dto.GenerationRequest;
import com.xy.generation.model.vo.GenerationEventVO;
import com.xy.generation.service.GenerationService;
import com.xy.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "应用生成")
@RestController
@RequestMapping("/apps/{appId}/generations")
public class GenerationController {

    private final GenerationService generationService;
    private final UserService userService;

    public GenerationController(GenerationService generationService, UserService userService) {
        this.generationService = generationService;
        this.userService = userService;
    }

    @Operation(summary = "流式生成单 HTML 网站", description = "仅创建者可调用；当前仅支持 html 模式。"
            + "同一应用同时只运行一次生成。SSE 依次返回 started、delta 和 completed 或 failed；"
            + "只有文件保存并更新成功版本后才发送 completed。开始前的错误返回普通 JSON。")
    @ApiResponse(responseCode = "200", description = "SSE 流，必须检查最终事件，不能仅凭 HTTP 200 判断生成成功",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = GenerationEventVO.class)))
    @PostMapping
    public SseEmitter generate(
            @Parameter(description = "应用 ID，必须大于 0", example = "55") @Positive @PathVariable long appId,
            @Valid @RequestBody GenerationRequest request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        servletResponse.setHeader("Cache-Control", "no-cache");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        return generationService.generate(appId, request, userService.getLoginUser(servletRequest));
    }
}
