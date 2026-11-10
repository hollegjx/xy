package com.xy.app.controller;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.common.ApiResponse;
import com.xy.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "应用")
@RestController
@RequestMapping("/apps")
public class AppController {

    private final AppService appService;
    private final UserService userService;

    public AppController(AppService appService, UserService userService) {
        this.appService = appService;
        this.userService = userService;
    }

    @Operation(summary = "创建应用", description = "需要登录；创建者取当前账号，名称取初始需求前 12 个字符，"
            + "默认多文件模式，也可选择 html。仅保存应用并返回字符串 ID；生成由后续接口发起。")
    @PostMapping
    public ApiResponse<String> createApp(@Valid @RequestBody AppAddRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(Long.toString(appService.createApp(request, userService.getLoginUser(servletRequest))));
    }

    @Operation(summary = "获取应用详情", description = "需要登录且为应用创建者；返回名称、初始需求、生成模式和时间。"
            + "其他账号（包括管理员）返回 HTTP 403，应用不存在或已删除时返回 HTTP 404。")
    @GetMapping("/{id}")
    public ApiResponse<AppVO> getAppById(
            @Parameter(description = "应用 ID，必须大于 0", example = "55")
            @Positive @PathVariable long id, HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.getAppById(id, userService.getLoginUser(servletRequest)));
    }
}
