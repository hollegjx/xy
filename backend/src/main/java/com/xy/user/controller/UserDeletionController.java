package com.xy.user.controller;

import com.xy.common.ApiResponse;
import com.xy.common.SessionUserResolver;
import com.xy.user.model.dto.UserIdentity;
import com.xy.user.service.UserDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户")
@RestController
@RequestMapping("/users")
public class UserDeletionController {
    private final UserDeletionService deletion;
    private final SessionUserResolver<UserIdentity> sessionUsers;

    public UserDeletionController(UserDeletionService deletion, SessionUserResolver<UserIdentity> sessionUsers) {
        this.deletion = deletion;
        this.sessionUsers = sessionUsers;
    }

    @Operation(summary = "管理员删除用户", description = "仅管理员可调用，无需请求体；只允许删除普通用户，管理员账号返回 HTTP 409。"
            + "先移除目标用户的公开作品，再将账号和所属应用一起逻辑删除；成功返回 true。"
            + "删除后账号不能登录，原 Session 在后续身份校验时失效；对话和私有源码保留。"
            + "目标不存在或已删除返回 HTTP 404，公开入口移除失败时保留账号供重试。")
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> deleteUser(
            @Parameter(description = "待删除的普通用户 ID，必须大于 0", example = "84")
            @Positive @PathVariable long id, HttpServletRequest servletRequest) {
        return ApiResponse.success(deletion.deleteUser(id, sessionUsers.require(servletRequest)));
    }
}
