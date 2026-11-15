package com.xy.user.controller;

import com.xy.common.ApiResponse;
import com.xy.user.model.dto.UserAddRequest;
import com.xy.user.model.dto.UserLoginRequest;
import com.xy.user.model.dto.UserQueryRequest;
import com.xy.user.model.dto.UserRegisterRequest;
import com.xy.user.model.dto.UserUpdateRequest;
import com.xy.user.model.vo.UserPageVO;
import com.xy.user.model.vo.UserVO;
import com.xy.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @Operation(summary = "管理员新增用户", description = "仅管理员可调用；填写账号、初始密码及可选昵称，只创建普通用户。"
            + "昵称留空时使用账号，账号重复时返回 HTTP 409 / 业务码 50001。"
            + "成功仅返回字符串用户 ID，不改变当前管理员的登录身份。")
    @PostMapping
    public ApiResponse<String> addUser(@Valid @RequestBody UserAddRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(Long.toString(userService.addUser(request, servletRequest)));
    }

    @Operation(summary = "管理员查询用户", description = "仅管理员可调用；支持账号模糊查询，默认第 1 页、每页 10 个。"
            + "按创建时间和 ID 倒序，只返回未删除用户的 ID、账号、昵称和角色。"
            + "未登录返回 HTTP 401，非管理员返回 HTTP 403。")
    @GetMapping
    public ApiResponse<UserPageVO> listUsers(
            @Valid @ModelAttribute @ParameterObject UserQueryRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(userService.listUsers(request, servletRequest));
    }

    @Operation(summary = "管理员修改用户昵称", description = "仅管理员可调用，可修改普通用户、其他管理员或本人的昵称。"
            + "昵称不能为空且最多 128 个字符；账号、密码和角色保持不变。"
            + "保存成功或昵称未变化时返回 true，目标不存在或已删除时返回 HTTP 404。")
    @PatchMapping("/{id}")
    public ApiResponse<Boolean> updateUser(
            @Parameter(description = "用户 ID，必须大于 0", example = "84")
            @Positive @PathVariable long id, @Valid @RequestBody UserUpdateRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(userService.updateUser(id, request, servletRequest));
    }

    @Operation(summary = "注册用户", description = "无需登录；两次密码须一致，账号重复时返回 HTTP 409 / 业务码 50001。仅创建普通用户，返回字符串形式的用户 ID。")
    @PostMapping("/register")
    public ApiResponse<String> register(@Valid @RequestBody UserRegisterRequest request) {
        return ApiResponse.success(Long.toString(userService.register(request)));
    }

    @Operation(summary = "用户登录", description = "无需登录；校验账号与密码，成功后建立 Session，返回不含密码散列的用户信息。")
    @PostMapping("/login")
    public ApiResponse<UserVO> login(@Valid @RequestBody UserLoginRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success(userService.login(request, servletRequest));
    }

    @Operation(summary = "用户退出", description = "需要登录；销毁当前 Session，成功返回 true。未登录、重复退出或账号已不可用时返回 HTTP 401。")
    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(HttpServletRequest request) {
        return ApiResponse.success(userService.logout(request));
    }

    @Operation(summary = "获取当前用户", description = "需要登录；通过 Session 查询当前有效账号，返回脱敏信息。未登录或账号已不可用时返回 HTTP 401。")
    @GetMapping("/me")
    public ApiResponse<UserVO> getCurrentUser(HttpServletRequest request) {
        return ApiResponse.success(userService.getUserVO(userService.getLoginUser(request)));
    }
}
