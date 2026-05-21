package com.remotesensing.platform.config;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.interceptor.AuthInterceptor;
import com.remotesensing.platform.config.interceptor.WorkerTokenInterceptor;
import com.remotesensing.platform.config.properties.AuthProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类，注册认证拦截器与 Worker 令牌拦截器。
 * <p>
 * 职责：
 * - 为 /tasks/*/claim 和 /tasks/*/status 添加 WorkerTokenInterceptor（仅允许携带有效 Worker Token 的 Python Worker 访问）。
 * - 为除白名单路径外的所有请求添加 AuthInterceptor（校验用户登录态）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 当前用户上下文，用于 AuthInterceptor 中解析请求中的用户身份。 */
    private final CurrentUserContext currentUserContext;

    /** 认证配置属性，用于 WorkerTokenInterceptor 校验 Worker Token。 */
    private final AuthProperties authProperties;

    public WebConfig(CurrentUserContext currentUserContext, AuthProperties authProperties) {
        this.currentUserContext = currentUserContext;
        this.authProperties = authProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Worker Token 拦截器：仅保护 Worker 回调接口，校验 X-Worker-Token 请求头
        registry.addInterceptor(new WorkerTokenInterceptor(authProperties))
                .addPathPatterns("/tasks/*/claim", "/tasks/*/status");

        // 认证拦截器：保护除登录/健康检查/Worker 回调外的所有接口
        registry.addInterceptor(new AuthInterceptor(currentUserContext))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/actuator/health",
                        "/tasks/*/claim",
                        "/tasks/*/status"
                );
    }
}
