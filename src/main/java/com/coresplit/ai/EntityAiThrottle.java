package com.coresplit.ai;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实体 AI 节流控制器。
 *
 * <p>核心机制：基于实体到最近玩家的距离，将实体分入 {@link AiBucket}，
 * 根据桶的 tickInterval 决定本 tick 是否执行 AI 逻辑。
 *
 * <p>全局倍率 {@code globalMultiplier} 由 {@link com.coresplit.governor.PerformanceGovernor}
 * 在质量降级时调整，进一步降低整体 AI 频率。
 */
public class EntityAiThrottle {

    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private final AtomicLong aiTickCount = new AtomicLong(0);
    private final AtomicLong aiSkippedCount = new AtomicLong(0);

    private volatile int nearRadius;
    private volatile int midRadius;
    private volatile int farRadius;
    private volatile boolean offscreenPause;
    private volatile boolean enabled = true;

    // 全局倍率：1.0=正常，0.5=减半频率，0.25=四分之一频率
    private volatile float globalMultiplier = 1.0f;

    public EntityAiThrottle(int nearRadius, int farRadius, boolean offscreenPause) {
        this.nearRadius = Math.max(1, nearRadius);
        this.midRadius = Math.max(this.nearRadius, farRadius / 2);
        this.farRadius = Math.max(this.midRadius, farRadius);
        this.offscreenPause = offscreenPause;
    }

    /**
     * 判断实体本 tick 是否应执行 AI。
     *
     * <p>由 MobMixin 在 Mob.tick() HEAD 处调用，返回 false 时短路 AI 逻辑。
     *
     * @param entityX 实体 X 坐标
     * @param entityY 实体 Y 坐标（未使用，保留接口）
     * @param entityZ 实体 Z 坐标
     * @param playerX 最近玩家 X
     * @param playerY 最近玩家 Y（未使用）
     * @param playerZ 最近玩家 Z
     * @return true 表示应执行 AI，false 表示跳过
     */
    public boolean shouldTickAi(double entityX, double entityY, double entityZ,
                                 double playerX, double playerY, double playerZ) {
        if (!enabled) {
            aiTickCount.incrementAndGet();
            return true;
        }

        int tick = tickCounter.get();
        double dx = entityX - playerX;
        double dz = entityZ - playerZ;
        double distSq = dx * dx + dz * dz;

        double nearSq = (double) nearRadius * nearRadius;
        double midSq = (double) midRadius * midRadius;
        double farSq = (double) farRadius * farRadius;

        AiBucket bucket = AiBucket.fromDistanceSq(distSq, nearSq, midSq, farSq);

        if (bucket == AiBucket.OFFSCREEN && offscreenPause) {
            aiSkippedCount.incrementAndGet();
            return false;
        }

        // 应用全局倍率：倍率越低，tickInterval 越大
        int effectiveInterval = bucket.tickInterval;
        if (globalMultiplier < 1.0f && globalMultiplier > 0) {
            effectiveInterval = Math.max(1, (int) (bucket.tickInterval / globalMultiplier));
        } else if (globalMultiplier == 0) {
            aiSkippedCount.incrementAndGet();
            return false;
        }

        if (effectiveInterval <= 1) {
            aiTickCount.incrementAndGet();
            return true;
        }

        // 按间隔节流
        boolean shouldTick = (tick % effectiveInterval) == 0;
        if (shouldTick) {
            aiTickCount.incrementAndGet();
        } else {
            aiSkippedCount.incrementAndGet();
        }
        return shouldTick;
    }

    /**
     * 每 tick 递增计数器。由 AiOptimizer 在 tick 回调中调用。
     */
    public void onTick() {
        tickCounter.incrementAndGet();
    }

    public AiBucket bucketFor(double entityX, double entityZ, double playerX, double playerZ) {
        double dx = entityX - playerX;
        double dz = entityZ - playerZ;
        double distSq = dx * dx + dz * dz;
        return AiBucket.fromDistanceSq(distSq,
                (double) nearRadius * nearRadius,
                (double) midRadius * midRadius,
                (double) farRadius * farRadius);
    }

    public void setGlobalMultiplier(float mult) {
        this.globalMultiplier = Math.max(0f, Math.min(1.0f, mult));
    }

    public float getGlobalMultiplier() {
        return globalMultiplier;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setNearRadius(int r) { this.nearRadius = Math.max(1, r); }
    public void setFarRadius(int r) {
        this.farRadius = Math.max(1, r);
        this.midRadius = Math.max(this.nearRadius, this.farRadius / 2);
    }
    public void setOffscreenPause(boolean v) { this.offscreenPause = v; }

    public int getNearRadius() { return nearRadius; }
    public int getFarRadius() { return farRadius; }

    public long getAiTickCount() { return aiTickCount.get(); }
    public long getAiSkippedCount() { return aiSkippedCount.get(); }

    /**
     * 计算节流率（跳过比例），用于监控。
     */
    public float throttleRate() {
        long total = aiTickCount.get() + aiSkippedCount.get();
        return total > 0 ? (float) aiSkippedCount.get() / total : 0f;
    }

    public void resetStats() {
        aiTickCount.set(0);
        aiSkippedCount.set(0);
    }
}
