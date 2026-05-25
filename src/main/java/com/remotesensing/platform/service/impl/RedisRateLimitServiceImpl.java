package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.RateLimitService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis Lua 脚本的滑动窗口限流实现，使用 ZSET 精确控制时间窗口。
 *
 * <p>与固定窗口（INCR + EXPIRE）的关键区别：
 * <ul>
 *   <li>固定窗口在窗口边界会出现流量突刺——前一个窗口的最后 1 秒和后一个窗口的
 *       第 1 秒请求不互斥，导致实际并发可能达到 2 倍阈值。</li>
 *   <li>滑动窗口每次请求都检查"最近 N 秒内"的数量，任意时间点的请求数都严格
 *       不超过阈值，没有边界突刺问题。</li>
 * </ul>
 *
 * <p>算法实现：
 * <ul>
 *   <li>ZSET 的 score 存储请求的毫秒时间戳，member 为 UUID 保证每次请求唯一。</li>
 *   <li>每次请求先 ZREMRANGEBYSCORE 移除窗口外的过期记录，再 ZCARD 统计窗口内数量，
 *       超限返回 0，否则 ZADD 加入本次请求。</li>
 *   <li>使用 Redis TIME 命令获取服务端统一时间，避免多实例时钟不同步。</li>
 *   <li>三步操作（清理 → 统计 → 加入）放在一个 Lua 脚本中原子执行，
 *       避免并发下的竞态条件。</li>
 * </ul>
 *
 * <p>容错策略：
 * <ul>
 *   <li>Redis 不可用时快速失败（fail-fast），抛出异常而非放行所有请求，
 *       确保限流策略不会被绕过。</li>
 *   <li>key 统一加 {@code rate-limit:} 前缀，方便在 Redis 中分类管理和监控。</li>
 * </ul>
 */
@Service
public class RedisRateLimitServiceImpl implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitServiceImpl.class);
    /** Redis key 前缀，统一标识限流类 key，便于集群中分类统计 */
    private static final String KEY_PREFIX = "rate-limit:";
    /**
     * 滑动窗口限流 Lua 脚本，使用 ZSET 实现精确的最近 N 秒内请求计数。
     *
     * <p>参数：
     * <ul>
     *   <li>KEYS[1] — ZSET 的 Redis key</li>
     *   <li>ARGV[1] — 窗口时长（毫秒）</li>
     *   <li>ARGV[2] — 窗口内允许的最大请求数</li>
     *   <li>ARGV[3] — 本次请求唯一标识（UUID），作为 ZSET member</li>
     * </ul>
     *
     * <p>执行流程：
     * <ol>
     *   <li>获取 Redis 服务端毫秒时间戳（TIME 命令）。</li>
     *   <li>ZREMRANGEBYSCORE 清理窗口外过期记录。</li>
     *   <li>ZCARD 统计窗口内剩余数量，超限返回 0。</li>
     *   <li>ZADD 将本次请求加入 ZSET（score = 毫秒时间戳）。</li>
     *   <li>PEXPIRE 刷新 key 过期时间，防止冷 key 占用内存。</li>
     * </ol>
     * 返回值：1 = 允许通过，0 = 已达上限。
     */

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local windowMillis = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            local member = ARGV[3]

            local nowTime = redis.call('TIME')
            local nowMillis = nowTime[1] * 1000 + math.floor(nowTime[2] / 1000)
            local minScore = nowMillis - windowMillis

            redis.call('ZREMRANGEBYSCORE', key, 0, minScore)

            local current = redis.call('ZCARD', key)
            if current >= limit then
                redis.call('PEXPIRE', key, windowMillis)
                return 0
            end

            redis.call('ZADD', key, nowMillis, member)
            redis.call('PEXPIRE', key, windowMillis)
            return 1
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    public RedisRateLimitServiceImpl(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取一个令牌（滑动窗口模式）。
     *
     * <p>参数前置校验在 Service 层完成，确保进入 Redis 调用的 key 合法。
     * 使用 UUID 作为 ZSET member 保证每次请求唯一，
     * 避免同一毫秒内多次请求被去重。</p>
     *
     * @param key    限流 Redis key（不含前缀）
     * @param limit  时间窗口内允许的最大请求数
     * @param window 限流时间窗口
     * @return true = 允许通过，false = 已达上限
     */
    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        // 参数防御性校验
        if (key == null || key.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "限流 key 不能为空");
        }
        if (limit <= 0 || window == null || window.isZero() || window.isNegative()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "限流配置不合法");
        }

        String redisKey = KEY_PREFIX + key;
        String member = UUID.randomUUID().toString();
        try {
            // 通过 Lua 脚本原子执行：清理过期 → 统计 → 加入，避免并发竞态
            Long allowed = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(redisKey),                  // KEYS[1]
                    String.valueOf(window.toMillis()),  // ARGV[1] — 窗口毫秒
                    String.valueOf(limit),              // ARGV[2] — 阈值
                    member                              // ARGV[3] — UUID
            );
            return Long.valueOf(1L).equals(allowed);
        } catch (RuntimeException exception) {
            // Redis 不可用时快速失败，不绕行限流策略
            log.warn("Redis 限流检查失败，key={}, reason={}", redisKey, exception.getMessage());
            throw new BusinessException(ResultCode.FAIL.getCode(), "系统繁忙，请稍后再试");
        }
    }

    /**
     * 检查是否超限，超限时抛出 TOO_MANY_REQUESTS (429) 异常。
     *
     * <p>Controller 层直接调用此方法，无需判断 {@link #tryAcquire} 返回值。</p>
     *
     * @param key    限流 Redis key
     * @param limit  时间窗口内允许的最大请求数
     * @param window 限流时间窗口
     * @throws BusinessException code=429 请求过于频繁
     */
    @Override
    public void check(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), ResultCode.TOO_MANY_REQUESTS.getMessage());
        }
    }
}
