package com.coresplit.explosion;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 爆炸粒子效果限制器，提供可配置的视觉效果等级。
 *
 * <p>三个等级控制单次爆炸产生的粒子数量上限：
 * <ul>
 *   <li>{@link ParticleLevel#LOW} - 8 个粒子，最低视觉冲击，最高性能</li>
 *   <li>{@link ParticleLevel#MEDIUM} - 32 个粒子，平衡视觉与性能</li>
 *   <li>{@link ParticleLevel#HIGH} - 128 个粒子，完整视觉效果</li>
 * </ul>
 */
public class ExplosionParticleLimiter {

    public enum ParticleLevel {
        LOW(8),
        MEDIUM(32),
        HIGH(128);

        public final int maxParticlesPerExplosion;

        ParticleLevel(int max) {
            this.maxParticlesPerExplosion = max;
        }
    }

    private volatile ParticleLevel level = ParticleLevel.MEDIUM;
    private final AtomicLong totalParticlesAllowed = new AtomicLong(0);
    private final AtomicLong totalParticlesBlocked = new AtomicLong(0);

    public ParticleLevel getLevel() {
        return level;
    }

    public void setLevel(ParticleLevel level) {
        if (level != null) {
            this.level = level;
        }
    }

    /**
     * 根据当前等级裁剪请求的粒子数。
     *
     * @param requested 请求的粒子数
     * @return 实际允许的粒子数（不超过当前等级上限）
     */
    public int limitParticles(int requested) {
        int max = level.maxParticlesPerExplosion;
        if (requested <= max) {
            totalParticlesAllowed.addAndGet(requested);
            return requested;
        }
        totalParticlesAllowed.addAndGet(max);
        totalParticlesBlocked.addAndGet(requested - max);
        return max;
    }

    public long getTotalParticlesAllowed() {
        return totalParticlesAllowed.get();
    }

    public long getTotalParticlesBlocked() {
        return totalParticlesBlocked.get();
    }

    public void resetStats() {
        totalParticlesAllowed.set(0);
        totalParticlesBlocked.set(0);
    }
}
