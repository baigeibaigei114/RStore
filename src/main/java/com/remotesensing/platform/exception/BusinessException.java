package com.remotesensing.platform.exception;

import com.remotesensing.platform.common.ResultCode;

/**
 * 业务异常：用于主动抛出可预期的业务错误。
 */
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
