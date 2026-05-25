package com.remotesensing.platform.common;

/**
 * 统一响应码，与前端约定 200 为成功、4xx 为客户端错误、500 为服务端错误。
 */
public enum ResultCode {

    /** 请求成功 */
    SUCCESS(200, "成功"),

    /** 请求参数校验失败（格式错误、缺少必填字段等） */
    PARAM_ERROR(400, "参数错误"),

    /** 用户未登录或 Token 已过期 */
    UNAUTHORIZED(401, "未登录或登录已过期"),

    /** 用户无权限访问该资源 */
    FORBIDDEN(403, "无权访问"),

    /** 触发限流，请求频率超过配置阈值 */
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),

    /** 服务端内部错误 */
    FAIL(500, "失败");

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
