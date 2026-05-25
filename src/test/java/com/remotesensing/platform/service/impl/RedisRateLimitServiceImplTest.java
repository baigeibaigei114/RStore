package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RedisRateLimitServiceImplTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    private RedisRateLimitServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RedisRateLimitServiceImpl(redisTemplate);
    }

    @Test
    void tryAcquireShouldAllowWhenRedisScriptReturnsOne() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate-limit:login:ip:127.0.0.1")), eq("60000"), eq("10"), any()))
                .thenReturn(1L);

        assertThat(service.tryAcquire("login:ip:127.0.0.1", 10, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void tryAcquireShouldRejectWhenLimitExceeded() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate-limit:login:ip:127.0.0.1")), eq("60000"), eq("10"), any()))
                .thenReturn(0L);

        assertThat(service.tryAcquire("login:ip:127.0.0.1", 10, Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void checkShouldThrowWhenLimitExceeded() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate-limit:task-submit:user:1")), eq("60000"), eq("10"), any()))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.check("task-submit:user:1", 10, Duration.ofSeconds(60)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ResultCode.TOO_MANY_REQUESTS.getMessage());
    }

    @Test
    void redisFailureShouldFailClosed() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.check("presigned-url:user:1", 60, Duration.ofSeconds(60)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统繁忙");
    }

    @Test
    void shouldPassWindowMillisAndLimitToLuaScript() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any())).thenReturn(1L);

        service.check("upload:user:1", 5, Duration.ofSeconds(300));

        ArgumentCaptor<String> windowCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> limitCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> memberCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("rate-limit:upload:user:1")),
                windowCaptor.capture(),
                limitCaptor.capture(),
                memberCaptor.capture()
        );
        assertThat(windowCaptor.getValue()).isEqualTo("300000");
        assertThat(limitCaptor.getValue()).isEqualTo("5");
        assertThat(memberCaptor.getValue()).isNotBlank();
    }
}
