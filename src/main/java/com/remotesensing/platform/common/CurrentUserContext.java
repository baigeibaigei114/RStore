package com.remotesensing.platform.common;

import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.service.TokenBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前用户上下文组件。
 *
 * <p>业务层统一从这里获取当前用户 ID，后续替换认证体系时不需要改动各个 Service。
 */
@Component
public class CurrentUserContext {

    public static final String USER_ID_HEADER = "X-User-Id";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final AuthProperties authProperties;
    private final TokenBlacklistService tokenBlacklistService;

    public CurrentUserContext(JwtTokenService jwtTokenService,
                              AuthProperties authProperties,
                              TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenService = jwtTokenService;
        this.authProperties = authProperties;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 统一解析当前用户身份标识。
     *
     * <p>解析顺序：1) JWT Bearer Token（检查黑名单）；2) 开发模式 X-User-Id 头；
     * 3) 开发模式默认用户 ID；均无法获取时抛出 UNAUTHORIZED 异常。
     *
     * @return 当前请求的用户 ID
     * @throws BusinessException 未登录或 Token 已失效时抛出
     */
    public String getCurrentUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String authorization = request.getHeader(AUTHORIZATION_HEADER);
            if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
                String token = authorization.substring(BEARER_PREFIX.length()).trim();
                JwtTokenService.JwtUser jwtUser = jwtTokenService.parseToken(token);
                if (tokenBlacklistService.isBlacklistedByJti(jwtUser.jti())) {
                    throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Token 已失效，请重新登录");
                }
                return jwtUser.userId();
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
