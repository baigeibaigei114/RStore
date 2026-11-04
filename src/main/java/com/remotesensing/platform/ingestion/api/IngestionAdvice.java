package com.remotesensing.platform.ingestion.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.ingestion.workflow.IngestionJson;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "com.remotesensing.platform.ingestion")
@org.springframework.core.annotation.Order(-20)
public class IngestionAdvice {
    public static ObjectNode response(IngestionProblem error) {
        ObjectNode data = IngestionJson.JSON.createObjectNode().put("reason",error.reason).put("retryable",error.retryable);
        if (error.currentVersion==null) data.putNull("currentVersion"); else data.put("currentVersion",error.currentVersion);
        return IngestionJson.JSON.createObjectNode().put("code",error.status).put("message","请求未完成，请根据状态处理").set("data",data);
    }
    @ExceptionHandler(IngestionProblem.class)
    public ResponseEntity<?> problem(IngestionProblem error) { return ResponseEntity.status(error.status).body(response(error)); }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException error) {
        int code = error.getCode();
        if (code<400 || code>599) code=400;
        return problem(new IngestionProblem(code,code==401?"UNAUTHENTICATED":code==403?"FORBIDDEN":"INVALID_REQUEST"));
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception error) { return problem(new IngestionProblem(400,"INVALID_REQUEST")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception error) { return problem(new IngestionProblem(503,"SERVICE_UNAVAILABLE")); }
}
