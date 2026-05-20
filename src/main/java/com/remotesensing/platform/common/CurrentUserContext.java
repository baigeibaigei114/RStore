package com.remotesensing.platform.common;

import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.JwtTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class CurrentUserContext {

    public static final String USER_ID_HEADER = "X-User-Id";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final AuthProperties authProperties;

    public CurrentUserContext(JwtTokenService jwtTokenService, AuthProperties authProperties) {
        this.jwtTokenService = jwtTokenService;
        this.authProperties = authProperties;
    }

    /**
     * 统一解析当前用户身份，业务层不直接读取请求头，方便后续替换为完整认证体系。
     */
    public String getCurrentUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String authorization = request.getHeader(AUTHORIZATION_HEADER);
            if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
                return jwtTokenService.parseToken(authorization.substring(BEARER_PREFIX.length()).trim()).userId();
            }

            if (authProperties.isDevUserHeaderEnabled()) {
                String userId = request.getHeader(USER_ID_HEADER);
                if (userId != null && !userId.isBlank()) {
                    return userId.trim();
                }
                return authProperties.getDefaultUserId();
            }
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
    }
}
