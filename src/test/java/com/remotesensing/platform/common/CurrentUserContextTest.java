package com.remotesensing.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.service.impl.JwtTokenServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CurrentUserContextTest {

    private AuthProperties authProperties;
    private JwtTokenService jwtTokenService;
    private CurrentUserContext currentUserContext;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authProperties.setJwtSecret("test-secret");
        authProperties.setDefaultUserId("dev-user");
        jwtTokenService = new JwtTokenServiceImpl(authProperties, new ObjectMapper());
        currentUserContext = new CurrentUserContext(jwtTokenService, authProperties);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("优先从 Bearer token 解析当前用户")
    void shouldResolveUserFromBearerToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenFor(1L, "admin", "ADMIN"));
        bindRequest(request);

        assertThat(currentUserContext.getCurrentUserId()).isEqualTo("1");
    }

    @Test
    @DisplayName("开发请求头关闭时 X-User-Id 不生效")
    void shouldRejectUserHeaderWhenDevFallbackDisabled() {
        authProperties.setDevUserHeaderEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserContext.USER_ID_HEADER, "user-a");
        bindRequest(request);

        assertThatThrownBy(() -> currentUserContext.getCurrentUserId())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ResultCode.UNAUTHORIZED.getMessage());
    }

    @Test
    @DisplayName("开发请求头开启时 X-User-Id 可作为 fallback")
    void shouldResolveUserHeaderWhenDevFallbackEnabled() {
        authProperties.setDevUserHeaderEnabled(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CurrentUserContext.USER_ID_HEADER, "user-a");
        bindRequest(request);

        assertThat(currentUserContext.getCurrentUserId()).isEqualTo("user-a");
    }

    @Test
    @DisplayName("开发请求头开启且没有 X-User-Id 时使用默认用户")
    void shouldUseDefaultUserWhenDevFallbackEnabled() {
        authProperties.setDevUserHeaderEnabled(true);
        bindRequest(new MockHttpServletRequest());

        assertThat(currentUserContext.getCurrentUserId()).isEqualTo("dev-user");
    }

    @Test
    @DisplayName("无 token 且未开启开发 fallback 时抛未登录异常")
    void shouldRejectAnonymousRequestWhenDevFallbackDisabled() {
        authProperties.setDevUserHeaderEnabled(false);
        bindRequest(new MockHttpServletRequest());

        assertThatThrownBy(() -> currentUserContext.getCurrentUserId())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ResultCode.UNAUTHORIZED.getMessage());
    }

    private void bindRequest(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private String tokenFor(Long userId, String username, String role) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setRole(role);
        return jwtTokenService.generateToken(user);
    }
}
