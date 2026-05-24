package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.service.TokenBlacklistService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的 Token 黑名单实现。
 *
 * <p>Redis 中只保存 jti，不保存完整 JWT，TTL 与 token 剩余有效期一致。
 */
@Service
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistServiceImpl.class);
    private static final String KEY_PREFIX = "auth:token:blacklist:";

    private final JwtTokenService jwtTokenService;
    private final RedisTemplate<String, String> redisTemplate;

    public TokenBlacklistServiceImpl(JwtTokenService jwtTokenService,
                                     RedisTemplate<String, String> redisTemplate) {
        this.jwtTokenService = jwtTokenService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void blacklist(String token) {
        JwtTokenService.JwtUser jwtUser = jwtTokenService.parseTokenIgnoringExpiration(token);
        long ttlSeconds = jwtUser.expiresAtEpochSecond() - Instant.now().getEpochSecond();
        if (ttlSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(jwtUser.jti()), jwtUser.userId(), Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException exception) {
            log.warn("写入 Token 黑名单失败，jti={}, reason={}", jwtUser.jti(), exception.getMessage());
            throw new BusinessException(ResultCode.FAIL.getCode(), "退出登录失败，请稍后重试");
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        JwtTokenService.JwtUser jwtUser = jwtTokenService.parseToken(token);
        return isBlacklistedByJti(jwtUser.jti());
    }

    @Override
    public boolean isBlacklistedByJti(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
        } catch (RuntimeException exception) {
            log.warn("查询 Token 黑名单失败，jti={}, reason={}", jti, exception.getMessage());
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 校验失败，请稍后重试");
        }
    }

    private String key(String jti) {
        if (jti == null || jti.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "token 缺少 jti");
        }
        return KEY_PREFIX + jti;
    }
}
