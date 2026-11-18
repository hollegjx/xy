package com.xy.app.controller;

import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.dto.AppAdminQueryRequest;
import com.xy.app.model.dto.AppQueryRequest;
import com.xy.app.model.dto.AppUpdateRequest;
import com.xy.app.model.vo.AppDeployVO;
import com.xy.app.model.vo.AppAdminPageVO;
import com.xy.app.model.vo.AppFeaturedVO;
import com.xy.app.model.vo.AppPageVO;
import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.common.ApiResponse;
import com.xy.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
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

    @Operation(summary = "管理员查询应用", description = "仅管理员可调用，支持名称模糊查询和创建者 ID 精确筛选；"
            + "省略条件时查询全平台未删除应用，默认第 1 页、每页 10 个，按创建时间和 ID 倒序排列。"
            + "仅返回应用摘要及已有发布信息，不包含初始需求、对话或源码；不开放他人工作台访问权限。"
            + "未登录返回 HTTP 401，普通用户返回 HTTP 403，非法分页或创建者 ID 返回 HTTP 400。")
    @GetMapping
    public ApiResponse<AppAdminPageVO> listAppsForAdmin(
            @Valid @ModelAttribute @ParameterObject AppAdminQueryRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.listAppsForAdmin(request, userService.getLoginUser(servletRequest)));
    }

    @Operation(summary = "查询我的应用", description = "需要登录；仅查询当前账号创建且未删除的应用，管理员也不例外。"
            + "支持名称模糊查询，默认第 1 页、每页 10 个，最多 20 个；按创建时间和 ID 倒序排列。"
            + "返回应用列表、总数和分页参数；已有发布信息随列表返回。")
    @GetMapping("/mine")
    public ApiResponse<AppPageVO> listMyApps(
            @Valid @ModelAttribute @ParameterObject AppQueryRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.listMyApps(request, userService.getLoginUser(servletRequest)));
    }

    @Operation(summary = "查询公开精选作品", description = "无需登录；按发布时间和 ID 倒序返回最多 6 个有效精选作品。"
            + "仅返回 ID、名称、生成模式和公开地址，不提供私有工作台内容；未配置公开站点时返回空数组。")
    @GetMapping("/featured")
    public ApiResponse<List<AppFeaturedVO>> listFeaturedApps() {
        return ApiResponse.success(appService.listFeaturedApps());
    }

    @Operation(summary = "获取应用详情", description = "需要登录且为应用创建者；返回名称、初始需求、生成模式、时间和发布信息。"
            + "其他账号（包括管理员）返回 HTTP 403，应用不存在或已删除时返回 HTTP 404。")
    @GetMapping("/{id}")
    public ApiResponse<AppVO> getAppById(
            @Parameter(description = "应用 ID，必须大于 0", example = "55")
            @Positive @PathVariable long id, HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.getAppById(id, userService.getLoginUser(servletRequest)));
    }

    @Operation(summary = "修改应用名称", description = "仅创建者可修改，管理员不能修改他人的应用。"
            + "名称不能为空且最多 128 个字符；只修改名称，不改变初始需求、生成模式或发布信息。"
            + "保存成功或名称未变化时返回 true。")
    @PatchMapping("/{id}")
    public ApiResponse<Boolean> updateApp(
            @Parameter(description = "应用 ID，必须大于 0", example = "55")
            @Positive @PathVariable long id, @Valid @RequestBody AppUpdateRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.updateApp(id, request, userService.getLoginUser(servletRequest)));
    }

    @Operation(summary = "删除应用", description = "仅创建者可删除，管理员不能删除他人的应用；无需请求体。"
            + "先移除已发布页面，再逻辑删除应用，成功返回 true。"
            + "删除后应用不再出现在列表中，详情、对话、预览和源码不可访问；不存在或已删除返回 404。")
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> deleteApp(
            @Parameter(description = "应用 ID，必须大于 0", example = "55")
            @Positive @PathVariable long id, HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.deleteApp(id, userService.getLoginUser(servletRequest)));
    }

    @Operation(summary = "发布应用", description = "仅创建者可发布最近成功版本，无需请求体。"
            + "返回独立静态站点地址，重新发布沿用原地址；新生成不会自动更新已发布页面。"
            + "Vue 发布已构建页面；无成功版本或该 Vue 版本尚无构建产物返回 409，原生网站文件缺失返回 404，未配置静态站点返回 503。")
    @PostMapping("/{id}/deploy")
    public ApiResponse<AppDeployVO> deployApp(
            @Parameter(description = "应用 ID，必须大于 0", example = "55")
            @Positive @PathVariable long id, HttpServletRequest servletRequest) {
        return ApiResponse.success(appService.deployApp(id, userService.getLoginUser(servletRequest)));
    }

    @Operation(summary = "下载应用源码", description = "仅创建者可下载最近成功版本；返回 ZIP 附件，错误返回统一 JSON。"
            + "html 模式只含 index.html，multi_file 模式包含原始 index.html、style.css、script.js。"
            + "vue_project 模式包含固定 Vue 工程骨架和 src 源码，不含依赖或构建产物。"
            + "不接受指定版本或文件路径；无成功版本返回 409，生成文件缺失返回 404。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "最近成功版本的源码 ZIP",
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{id}/source")
    public ResponseEntity<byte[]> downloadSource(
            @Parameter(description = "应用 ID，必须大于 0", example = "55")
            @Positive @PathVariable long id, HttpServletRequest servletRequest) {
        byte[] source = appService.downloadSource(id, userService.getLoginUser(servletRequest));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(source.length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("xy-app-" + id + ".zip").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(source);
    }
}
