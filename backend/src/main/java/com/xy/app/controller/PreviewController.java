package com.xy.app.controller;

import com.xy.app.service.PreviewService;
import com.xy.common.SessionUserResolver;
import com.xy.user.model.dto.UserIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "应用预览")
@RestController
@RequestMapping("/apps/{appId}/preview")
public class PreviewController {

    // 用响应头强制沙箱；不授予 allow-same-origin，直接打开页面也不能获得平台来源的权限。
    private static final String PREVIEW_POLICY = "sandbox allow-scripts; default-src 'none'; "
            + "script-src 'unsafe-inline' data:; style-src 'unsafe-inline' data:; img-src data: blob:; "
            + "font-src data:; media-src data: blob:; connect-src 'none'; "
            + "form-action 'none'; base-uri 'none'; frame-ancestors 'self'";
    private final PreviewService previewService;
    private final SessionUserResolver<UserIdentity> sessionUsers;

    public PreviewController(PreviewService previewService, SessionUserResolver<UserIdentity> sessionUsers) {
        this.previewService = previewService;
        this.sessionUsers = sessionUsers;
    }

    @Operation(summary = "预览最近成功版本", description = "仅创建者可访问；支持 html、multi_file 和已构建的 vue_project，直接返回 UTF-8 HTML；Vue 缺少构建产物返回 409，"
            + "错误返回普通 JSON。无成功版本返回 409，生成文件缺失返回 404。多文件 CSS/JS 编码为 data URL，"
            + "沙箱阻止读取平台 Cookie、存储和父页面，禁止联网与表单提交；iframe 应通过同源 /api 地址加载。")
    @ApiResponse(responseCode = "200", description = "隔离运行的 HTML；在浏览器直接访问本接口可预览",
            content = @Content(mediaType = "text/html", schema = @Schema(type = "string")))
    @GetMapping
    public ResponseEntity<String> getPreview(
            @Parameter(description = "应用 ID，必须大于 0", example = "55") @Positive @PathVariable long appId,
            @Parameter(description = "工作台是否启用元素点选") @RequestParam(defaultValue = "false") boolean pick,
            HttpServletRequest request) throws IOException {
        String html = previewService.getPreview(appId, sessionUsers.require(request));
        if (pick) {
            html += new ClassPathResource("preview/element-picker.html").getContentAsString(StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", PREVIEW_POLICY)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(html);
    }
}
