package com.remotesensing.platform.config;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.interceptor.AuthInterceptor;
import com.remotesensing.platform.config.interceptor.WorkerTokenInterceptor;
import com.remotesensing.platform.config.properties.AuthProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserContext currentUserContext;
    private final AuthProperties authProperties;

    public WebConfig(CurrentUserContext currentUserContext, AuthProperties authProperties) {
        this.currentUserContext = currentUserContext;
        this.authProperties = authProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WorkerTokenInterceptor(authProperties))
                .addPathPatterns("/tasks/*/claim", "/tasks/*/status");

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
