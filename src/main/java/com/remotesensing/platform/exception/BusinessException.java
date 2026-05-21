package com.remotesensing.platform.exception;

import com.remotesensing.platform.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常：用于主动抛出可预期的业务错误。
 * <p>
 * 与 {@link GlobalExceptionHandler} 配合，将业务异常转换为统一的 {@link com.remotesensing.platform.common.Result} 响应。
 * 包含自定义错误码，便于前端区分不同类型的业务错误。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码，对应 Result 中的 code 字段。 */
    private final Integer code;

    /**
     * 使用默认失败码（500）构造业务异常。
     *
     * @param message 错误描述
     */
    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
    }

    /**
     * 使用自定义错误码构造业务异常。
     *
     * @param code    自定义错误码
     * @param message 错误描述
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
