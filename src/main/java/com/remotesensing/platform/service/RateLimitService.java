package com.remotesensing.platform.service;

import com.remotesensing.platform.exception.BusinessException;

import java.time.Duration;

/**
 * 接口限流服务，基于滑动窗口统计请求频率，超过阈值时拒绝请求。
 *
 * <p>调用方自行构造 Redis key（如 "login:ip:192.168.1.1"），
 * 服务只负责执行原子计数逻辑，不关心 key 的语义。</p>
 */
public interface RateLimitService {

    /**
     * 尝试获取一个令牌，成功返回 true，超限返回 false。
     *
     * @param key    限流 Redis key（由调用方构造）
     * @param limit  时间窗口内允许的最大请求数
     * @param window 限流时间窗口
     * @return true = 允许通过，false = 已达上限
     */
    boolean tryAcquire(String key, int limit, Duration window);

    /**
     * 检查是否超限，超限时直接抛出 TOO_MANY_REQUESTS 异常。
     *
     * <p>适合 Controller 层直接调用，无需手动处理返回值。</p>
     *
     * @param key    限流 Redis key
     * @param limit  时间窗口内允许的最大请求数
     * @param window 限流时间窗口
     * @throws BusinessException code=429 请求过于频繁
     */
    void check(String key, int limit, Duration window);
}
