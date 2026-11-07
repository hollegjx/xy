package com.xy.health;

import com.xy.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "健康检查")
@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @Operation(summary = "检查后端与数据库连接", description = "无需登录；成功时返回当前连接的数据库名称，数据库不可用时返回 HTTP 503。")
    @GetMapping("/health")
    public ApiResponse<HealthVO> health() {
        return ApiResponse.success(healthService.check());
    }
}
