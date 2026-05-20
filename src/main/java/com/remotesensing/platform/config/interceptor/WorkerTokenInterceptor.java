package com.remotesensing.platform.config.interceptor;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class WorkerTokenInterceptor implements HandlerInterceptor {

    public static final String WORKER_TOKEN_HEADER = "X-Worker-Token";

    private final AuthProperties authProperties;

    public WorkerTokenInterceptor(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String token = request.getHeader(WORKER_TOKEN_HEADER);
        if (token == null || !token.equals(authProperties.getWorkerToken())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "Worker Token 无效");
        }
        return true;
    }
}
