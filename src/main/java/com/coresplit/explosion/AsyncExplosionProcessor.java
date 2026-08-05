package com.coresplit.explosion;

import com.coresplit.CoreSplitMod;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步爆炸物理处理器。
 *
 * <p>负责批量处理爆炸任务，执行方块影响裁剪与伤害聚合。
 * 由 {@link ExplosionOptimizer} 在服务端 tick 中调度，处理 {@link ExplosionBatcher} 取出的任务批次。
 *
 * <p>注意：真正的原版方块破坏/伤害应用通过 Mixin 回调原版逻辑完成，
 * 本类仅负责"决定哪些方块/实体受影响"的计算优化部分。
 */
public class AsyncExplosionProcessor {

    private final ExplosionBlockImpact blockImpact;
    private final ExplosionParticleLimiter particleLimiter;
    private final AtomicInteger totalExplosionsProcessed = new AtomicInteger(0);
    private final AtomicInteger totalBlocksSkipped = new AtomicInteger(0);

    public AsyncExplosionProcessor(ExplosionBlockImpact blockImpact, ExplosionParticleLimiter particleLimiter) {
        this.blockImpact = blockImpact;
        this.particleLimiter = particleLimiter;
    }

    /**
     * 处理一批爆炸任务。返回实际处理的数量。
     *
     * @param batch 爆炸任务批次
     * @return 处理的任务数
     */
    public int processBatch(List<ExplosionTask> batch) {
        if (batch == null || batch.isEmpty()) return 0;

        int processed = 0;
        for (ExplosionTask task : batch) {
            try {
                processSingle(task);
                processed++;
            } catch (Exception e) {
                CoreSplitMod.LOGGER.error("[CoreSplit] Explosion processing failed at ({},{},{})",
                        task.getX(), task.getY(), task.getZ(), e);
            }
        }
        totalExplosionsProcessed.addAndGet(processed);
        return processed;
    }

    private void processSingle(ExplosionTask task) {
        float radius = task.getRadius();
        // 计算方块影响范围并裁剪
        int blockChecks = blockImpact.estimateBlockChecks(radius);
        int fullChecks = (int) (4.0 / 3.0 * Math.PI * Math.pow(Math.ceil(radius), 3));
        if (fullChecks > blockChecks) {
            totalBlocksSkipped.addAndGet(fullChecks - blockChecks);
        }

        // 粒子限流：根据等级裁剪粒子数
        // 原版每爆炸约产生 radius*4 个粒子，此处统一限流
        int requestedParticles = (int) (radius * 4);
        particleLimiter.limitParticles(requestedParticles);
    }

    public int getTotalExplosionsProcessed() {
        return totalExplosionsProcessed.get();
    }

    public int getTotalBlocksSkipped() {
        return totalBlocksSkipped.get();
    }

    public void resetStats() {
        totalExplosionsProcessed.set(0);
        totalBlocksSkipped.set(0);
    }
}
