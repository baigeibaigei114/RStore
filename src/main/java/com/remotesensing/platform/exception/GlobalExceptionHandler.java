package com.remotesensing.platform.exception;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.common.ResultCode;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException exception) {
        return Result.fail(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidationException(Exception exception) {
        // 先返回通用参数错误，后续有具体 DTO 后可补充字段级错误信息。
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), ResultCode.PARAM_ERROR.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameterException(MissingServletRequestParameterException exception) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "缺少必要参数：" + exception.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException exception) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "参数格式错误：" + exception.getName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException exception) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "上传文件超过大小限制");
    }

    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipartException(MultipartException exception) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "文件上传请求格式错误，请使用 multipart/form-data");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception) {
        return Result.fail(ResultCode.FAIL.getCode(), "系统异常，请联系管理员");
    }
}
