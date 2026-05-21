package com.remotesensing.platform.config.interceptor;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Worker 令牌拦截器。
 * <p>
 * 职责：
 * - 校验 Python Worker 回调请求（claim / status 更新）中携带的 X-Worker-Token 头。
 * - 防止未经授权的客户端模拟 Worker 抢占任务或篡改任务状态。
 * <p>
 * 应用于 /tasks/*/claim 和 /tasks/*/status 路径。
 */
public class WorkerTokenInterceptor implements HandlerInterceptor {

    /** 请求头名称：Worker 身份令牌。 */
    public static final String WORKER_TOKEN_HEADER = "X-Worker-Token";

    /** 认证配置，包含 workerToken 值。 */
    private final AuthProperties authProperties;

    public WorkerTokenInterceptor(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // OPTIONS 请求（CORS 预检）不校验令牌
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
