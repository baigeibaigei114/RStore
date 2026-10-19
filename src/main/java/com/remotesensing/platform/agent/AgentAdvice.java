package com.remotesensing.platform.agent;

import com.remotesensing.platform.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

/** Prevent framework/provider exception details from entering Agent browser responses or logs. */
@Order(-10)
@RestControllerAdvice(assignableTypes = AgentController.class)
public class AgentAdvice {
    @ExceptionHandler(AgentFailure.class)
    public Result<?> failure(AgentFailure error, HttpServletRequest request) {
        Result<com.fasterxml.jackson.databind.JsonNode> result = new Result<>();
        result.setCode(error.getCode()); result.setMessage(error.getMessage());
        result.setData(AgentJson.object().put("requestId", String.valueOf(request.getAttribute(AgentBoundary.REQUEST_ID)))
                .put("errorCode", error.errorCode).put("retryable", error.retryable));
        return result;
    }
    @ExceptionHandler(com.remotesensing.platform.exception.BusinessException.class)
    public Result<?> business(com.remotesensing.platform.exception.BusinessException error) { return Result.fail(error.getCode(), "Agent请求被拒绝，请核对状态或稍后重试"); }
    @ExceptionHandler(Exception.class)
    public Result<?> unexpected(Exception error) { return Result.fail(500, "Agent操作失败，请刷新状态后重试"); }
}
