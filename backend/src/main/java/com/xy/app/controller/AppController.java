package com.xy.app.controller;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.service.AppService;
import com.xy.common.ApiResponse;
import com.xy.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
}
