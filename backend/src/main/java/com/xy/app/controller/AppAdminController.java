package com.xy.app.controller;

import com.xy.app.model.dto.AppUpdateRequest;
import com.xy.app.service.AppService;
import com.xy.common.ApiResponse;
import com.xy.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "应用管理")
@RestController
@RequestMapping("/admin/apps")
public class AppAdminController {

    private final AppService appService;
    private final UserService userService;

    public AppAdminController(AppService appService, UserService userService) {
        this.appService = appService;
        this.userService = userService;
    }

    @Operation(summary = "管理员修改应用名称", description = "仅管理员可修改任一有效应用的名称；"
            + "名称不能为空且最多 128 个字符，成功或名称未变化时返回 true。"
            + "只修改名称，不改变创建者、初始需求、生成模式或发布信息，也不开放私有工作台权限。"
            + "未登录返回 HTTP 401，普通用户返回 HTTP 403，不存在或已删除返回 HTTP 404。")
    @PatchMapping("/{id}")
    public ApiResponse<Boolean> updateAppForAdmin(
            @Parameter(description = "应用 ID，必须大于 0", example = "55")
            @Positive @PathVariable long id, @Valid @RequestBody AppUpdateRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.updateAppForAdmin(id, request,
                userService.getLoginUser(servletRequest)));
    }

    @Operation(summary = "管理员删除应用", description = "仅管理员可删除任一有效应用；"
            + "先下线公开作品，再逻辑删除应用，成功返回 true；保留私有对话和生成文件。"
            + "未登录返回 HTTP 401，普通用户返回 HTTP 403，不存在或已删除返回 HTTP 404，"
            + "非正数 ID 返回 HTTP 400；公开入口移除失败时保留应用并返回错误。")
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> deleteAppForAdmin(
            @Parameter(description = "应用 ID，必须大于 0", example = "55")
            @Positive @PathVariable long id, HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.deleteAppForAdmin(id, userService.getLoginUser(servletRequest)));
    }
}
