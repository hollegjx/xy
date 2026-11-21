package com.xy.common;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "统一接口响应")
public record ApiResponse<T>(
        @Schema(description = "业务状态码，0 表示成功", example = "0") int code,
        @Schema(description = "响应数据") T data,
        @Schema(description = "结果说明", example = "ok") String message) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, data, "ok");
    }

    public static ApiResponse<Void> failure(int code, String message) {
        return new ApiResponse<>(code, null, message);
    }
}
