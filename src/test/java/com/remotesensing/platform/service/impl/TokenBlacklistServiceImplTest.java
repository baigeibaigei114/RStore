package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.JwtTokenService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceImplTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private JwtTokenService jwtTokenService;
    private TokenBlacklistServiceImpl service;

    @BeforeEach
    void setUp() {
        AuthProperties authProperties = new AuthProperties();
        authProperties.setJwtSecret("test-secret");
        authProperties.setTokenExpireMinutes(60);
        jwtTokenService = new JwtTokenServiceImpl(authProperties, new ObjectMapper());
        service = new TokenBlacklistServiceImpl(jwtTokenService, redisTemplate);
    }

    @Test
    void blacklistShouldStoreJtiWithRemainingTtl() {
        String token = tokenFor(1L);
        JwtTokenService.JwtUser jwtUser = jwtTokenService.parseToken(token);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.blacklist(token);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                eq("auth:token:blacklist:" + jwtUser.jti()),
                eq("1"),
                ttlCaptor.capture()
        );
        assertThat(ttlCaptor.getValue().getSeconds()).isPositive();
    }

    @Test
    void isBlacklistedShouldReturnRedisResult() {
        String token = tokenFor(1L);
        JwtTokenService.JwtUser jwtUser = jwtTokenService.parseToken(token);
        when(redisTemplate.hasKey("auth:token:blacklist:" + jwtUser.jti())).thenReturn(true);

        assertThat(service.isBlacklisted(token)).isTrue();
    }

    @Test
    void expiredTokenShouldNotBeWrittenToRedis() {
        AuthProperties authProperties = new AuthProperties();
        authProperties.setJwtSecret("test-secret");
        authProperties.setTokenExpireMinutes(-1);
        JwtTokenService expiredTokenService = new JwtTokenServiceImpl(authProperties, new ObjectMapper());
        TokenBlacklistServiceImpl expiredService = new TokenBlacklistServiceImpl(expiredTokenService, redisTemplate);

        expiredService.blacklist(tokenFor(expiredTokenService, 1L));

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void redisFailureShouldRejectLogout() {
        String token = tokenFor(1L);
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.blacklist(token))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退出登录失败");
    }

    @Test
    void redisFailureShouldRejectBlacklistCheck() {
        String token = tokenFor(1L);
        when(redisTemplate.hasKey(any())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.isBlacklisted(token))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("token 校验失败");
    }

    private String tokenFor(Long userId) {
        return tokenFor(jwtTokenService, userId);
    }

    private String tokenFor(JwtTokenService tokenService, Long userId) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("admin");
        user.setRole("ADMIN");
        return tokenService.generateToken(user);
    }
}
