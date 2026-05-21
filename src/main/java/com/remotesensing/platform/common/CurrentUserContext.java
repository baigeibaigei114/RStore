package com.remotesensing.platform.common;

import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.JwtTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前用户上下文组件。
 * <p>
 * 职责：
 * - 统一从当前请求上下文中解析用户身份标识（userId）。
 * - 支持两种认证方式：
 *   1. JWT Bearer Token（正式环境）。
 *   2. X-User-Id 请求头（开发模式）。
 * - 业务层不直接读取请求头，通过本组件解耦，方便后续替换认证体系。
 */
@Component
public class CurrentUserContext {

    /** 开发模式下用于传递用户 ID 的请求头名称。 */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** 标准 Authorization 请求头名称。 */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer Token 前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** JWT Token 服务，用于解析和下 Bearer Token。 */
    private final JwtTokenService jwtTokenService;

    /** 认证配置属性，用于判断是否启用开发模式。 */
    private final AuthProperties authProperties;

    public CurrentUserContext(JwtTokenService jwtTokenService, AuthProperties authProperties) {
        this.jwtTokenService = jwtTokenService;
        this.authProperties = authProperties;
    }

    /**
     * 统一解析当前用户身份，业务层不直接读取请求头，方便后续替换为完整认证体系。
     * <p>
     * 解析顺序：
     * 1. 优先从 Authorization: Bearer <token> 中解析用户 ID。
     * 2. 如果启用了 devUserHeaderEnabled，降级为从 X-User-Id 头获取。
     * 3. 若 X-User-Id 为空，使用默认用户 ID。
     * 4. 若均无法获取，抛出 UNAUTHORIZED 异常。
     *
     * @return 当前请求的用户 ID
     * @throws BusinessException 未登录或 Token 无效时抛出
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
