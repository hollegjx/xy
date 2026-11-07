package com.xy.user.controller;

import com.xy.common.ApiResponse;
import com.xy.user.model.dto.UserRegisterRequest;
import com.xy.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户")
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "注册用户", description = "无需登录；两次密码须一致，账号不能重复。仅创建普通用户，返回字符串形式的用户 ID。")
    @PostMapping("/register")
    public ApiResponse<String> register(@Valid @RequestBody UserRegisterRequest request) {
        return ApiResponse.success(Long.toString(userService.register(request)));
    }
}
