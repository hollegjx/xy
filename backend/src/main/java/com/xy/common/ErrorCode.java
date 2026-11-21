package com.xy.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    PARAMS_ERROR(40000, HttpStatus.BAD_REQUEST),
    NOT_LOGIN_ERROR(40100, HttpStatus.UNAUTHORIZED),
    NO_AUTH_ERROR(40300, HttpStatus.FORBIDDEN),
    NOT_FOUND_ERROR(40400, HttpStatus.NOT_FOUND),
    TOO_MANY_REQUESTS(42900, HttpStatus.TOO_MANY_REQUESTS),
    OPERATION_ERROR(50001, HttpStatus.CONFLICT),
    SYSTEM_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final HttpStatus status;

    ErrorCode(int code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
