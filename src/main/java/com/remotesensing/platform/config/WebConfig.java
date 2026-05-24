package com.remotesensing.platform.config;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.interceptor.AuthInterceptor;
import com.remotesensing.platform.config.interceptor.WorkerTokenInterceptor;
import com.remotesensing.platform.config.properties.AuthProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类，负责注册用户认证拦截器和 Worker Token 拦截器。
 *
 * <p>Worker 回调接口只校验 Worker Token，普通业务接口校验登录用户身份。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 当前用户上下文，用于 AuthInterceptor 解析请求中的用户身份。 */
    private final CurrentUserContext currentUserContext;

    /** 认证配置属性，用于 WorkerTokenInterceptor 校验 Worker Token。 */
    private final AuthProperties authProperties;

    public WebConfig(CurrentUserContext currentUserContext, AuthProperties authProperties) {
        this.currentUserContext = currentUserContext;
        this.authProperties = authProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Worker Token 拦截器：只保护 Worker 回调接口，校验 X-Worker-Token 请求头。
        registry.addInterceptor(new WorkerTokenInterceptor(authProperties))
                .addPathPatterns("/tasks/*/claim", "/tasks/*/status");

        // 用户认证拦截器：保护除登录、健康检查、Worker 回调外的所有接口。
        registry.addInterceptor(new AuthInterceptor(currentUserContext))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/logout",
                        "/actuator/health",
                        "/tasks/*/claim",
                        "/tasks/*/status"
                );
    }
}
