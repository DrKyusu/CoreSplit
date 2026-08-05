package com.coresplit.compat.remote;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限速器。
 *
 * <p>用于约束日志告警频率（项目硬约束：日志警告 ≤10/10秒），防止网络/反射失败时
 * 日志风暴。也用于 GitHub API 调用计数。
 *
 * <p>线程安全：用 AtomicInteger + AtomicLong + CAS 实现无锁限速。
 */
public class RateLimiter {

    private final int maxTokens;
    private final long refillIntervalMs;
    private final AtomicInteger tokens;
    private final AtomicLong lastRefillAt;

    /**
     * @param maxTokens        令牌桶容量（每个 refill 周期内最多通过 maxTokens 次）
     * @param refillIntervalMs 令牌重置周期（毫秒）
     */
    public RateLimiter(int maxTokens, long refillIntervalMs) {
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
        if (refillIntervalMs <= 0) throw new IllegalArgumentException("refillIntervalMs must be > 0");
        this.maxTokens = maxTokens;
        this.refillIntervalMs = refillIntervalMs;
        this.tokens = new AtomicInteger(maxTokens);
        this.lastRefillAt = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * 尝试获取一个令牌。成功返回 true，被限速返回 false。
     * 线程安全，无锁。
     */
    public boolean tryAcquire() {
        refillIfNeeded();
        while (true) {
            int cur = tokens.get();
            if (cur <= 0) return false;
            if (tokens.compareAndSet(cur, cur - 1)) return true;
        }
    }

    /**
     * 重置令牌桶为满（用于测试或手动恢复）。
     */
    public void reset() {
        tokens.set(maxTokens);
        lastRefillAt.set(System.currentTimeMillis());
    }

    public int getAvailableTokens() {
        refillIfNeeded();
        return Math.max(0, tokens.get());
    }

    private void refillIfNeeded() {
        long now = System.currentTimeMillis();
        long last = lastRefillAt.get();
        if (now - last >= refillIntervalMs) {
            // CAS 保证只有一个线程执行重置
            if (lastRefillAt.compareAndSet(last, now)) {
                tokens.set(maxTokens);
            }
        }
    }
}
