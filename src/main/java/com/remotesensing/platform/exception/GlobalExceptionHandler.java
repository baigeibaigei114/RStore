package com.remotesensing.platform.exception;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.common.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * 全局异常处理器，为所有 Controller 提供统一的异常处理。
 * <p>
 * 将各类异常转换为标准的 {@link Result} 响应，避免将堆栈信息暴露给前端。
 * 处理范围包括：业务异常、参数校验异常、参数缺失、类型转换错误、文件上传异常以及未预料的运行时异常。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 处理主动抛出的业务异常，使用异常中携带的自定义错误码。 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException exception) {
        return Result.fail(exception.getCode(), exception.getMessage());
    }

    /** 处理 @Valid / @Validated 校验失败异常（DTO 字段校验），返回通用参数错误信息。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidationException(Exception exception) {
        // 先返回通用参数错误，后续有具体 DTO 后可补充字段级错误信息。
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), ResultCode.PARAM_ERROR.getMessage());
    }

    /** 处理请求缺少必要参数（如未传 @RequestParam）。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameterException(MissingServletRequestParameterException exception) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "缺少必要参数：" + exception.getParameterName());
    }

    /** 处理参数类型转换错误（如 String 转 Integer 失败）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException exception) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "参数格式错误：" + exception.getName());
    }

    /** 处理上传文件超过配置大小限制。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException exception) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "上传文件超过大小限制");
    }

    /** 处理请求 Content-Type 不是 multipart/form-data 的情况。 */
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipartException(MultipartException exception) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "文件上传请求格式错误，请使用 multipart/form-data");
    }

    /** 兜底处理器，捕获所有未预料的异常，记录完整堆栈后返回通用错误信息。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception) {
        log.error("Unhandled request exception", exception);
        return Result.fail(ResultCode.FAIL.getCode(), "系统异常，请联系管理员");
    }
}
