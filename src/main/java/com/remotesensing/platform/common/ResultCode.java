package com.remotesensing.platform.common;

/**
 * 统一维护常用响应码，后续可按业务模块继续扩展。
 */
public enum ResultCode {

    SUCCESS(200, "success"),
    FAIL(500, "fail"),
    PARAM_ERROR(400, "参数错误");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
