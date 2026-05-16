package com.remotesensing.platform.common;

import com.remotesensing.platform.service.JwtTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class CurrentUserContext {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String DEFAULT_USER_ID = "dev-user";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;

    public CurrentUserContext(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * 当前阶段从请求头读取用户标识，后续接入认证体系时只需替换这一处。
     */
    public String getCurrentUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String authorization = request.getHeader(AUTHORIZATION_HEADER);
            if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
                return jwtTokenService.parseToken(authorization.substring(BEARER_PREFIX.length()).trim()).userId();
            }

            String userId = request.getHeader(USER_ID_HEADER);
            if (userId != null && !userId.isBlank()) {
                return userId.trim();
            }
        }
        return DEFAULT_USER_ID;
    }
}
