package com.remotesensing.platform.config.interceptor;

import com.remotesensing.platform.common.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户认证拦截器。
 * <p>
 * 职责：
 * - 对所有非 OPTIONS 请求校验用户身份（通过 JWT Token 或开发模式下的 X-User-Id 头）。
 * - 校验失败时由 CurrentUserContext 抛出 BusinessException(UNAUTHORIZED)。
 * <p>
 * 注意：本拦截器不阻断请求（始终返回 true），但会在未认证时抛异常，
 * 由 GlobalExceptionHandler 统一转换为错误响应。
 */
public class AuthInterceptor implements HandlerInterceptor {

    /** 当前用户上下文，用于解析请求中的用户身份标识。 */
    private final CurrentUserContext currentUserContext;

    public AuthInterceptor(CurrentUserContext currentUserContext) {
        this.currentUserContext = currentUserContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // OPTIONS 请求（CORS 预检）不校验身份
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // 解析当前用户 ID，若未认证则直接抛出异常
        currentUserContext.getCurrentUserId();
        return true;
    }
}
