package com.coresplit.explosion;

import com.coresplit.CoreSplitMod;
import com.coresplit.scheduler.PrioritizedTaskScheduler;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 爆炸优化模块门面（单例）。
 *
 * <p>职责：
 * <ol>
 *   <li>接收 Mixin 拦截的爆炸事件，封装为 {@link ExplosionTask} 提交到 {@link ExplosionBatcher}</li>
 *   <li>在服务端/客户端 tick 中驱动分帧处理</li>
 *   <li>模块禁用时不拦截原版爆炸，确保可回退</li>
 * </ol>
 */
public class ExplosionOptimizer {

    private static volatile ExplosionOptimizer instance;

    private final ExplosionBatcher batcher;
    private final AsyncExplosionProcessor processor;
    private final ExplosionParticleLimiter particleLimiter;
    private final ExplosionBlockImpact blockImpact;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    private ExplosionOptimizer() {
        ExplosionConfig config = ExplosionConfig.getInstance();
        this.batcher = new ExplosionBatcher(config.getMaxPerFrame(), config.getMaxPerTick());
        this.particleLimiter = new ExplosionParticleLimiter();
        this.particleLimiter.setLevel(config.getParticleLevelEnum());
        this.blockImpact = new ExplosionBlockImpact(config.isSkipFarBlockImpact(), config.getFarBlockDistance());
        this.processor = new AsyncExplosionProcessor(blockImpact, particleLimiter);
        this.enabled.set(config.isEnabled());
    }

    public static ExplosionOptimizer getInstance() {
        ExplosionOptimizer result = instance;
        if (result == null) {
            synchronized (ExplosionOptimizer.class) {
                result = instance;
                if (result == null) {
                    result = new ExplosionOptimizer();
                    instance = result;
                }
            }
        }
        return result;
    }

    /**
     * 提交一个爆炸事件。由 Mixin 在拦截原版爆炸时调用。
     *
     * @param x 爆炸中心 X
     * @param y 爆炸中心 Y
     * @param z 爆炸中心 Z
     * @param radius 爆炸半径
     * @param distanceToNearestPlayer 到最近玩家的距离（用于优先级排序）
     * @return true 表示已接管处理，Mixin 应取消原版逻辑；false 表示不拦截，走原版
     */
    public boolean submit(double x, double y, double z, float radius, double distanceToNearestPlayer) {
        if (!enabled.get()) {
            return false;
        }
        ExplosionTask task = new ExplosionTask(x, y, z, radius, distanceToNearestPlayer);
        // 修复BUG: 原代码忽略 offer 返回值，队列满时仍返回 true。
        // 现返回 offer 实际结果，调用方（Mixin）可据此决定是否接管。
        return batcher.offer(task);
    }

    /**
     * 服务端 tick 回调，驱动爆炸分帧处理。
     *
     * @param scheduler 优先级任务调度器，用于异步处理
     */
    public void onServerTick(PrioritizedTaskScheduler scheduler) {
        if (!enabled.get()) return;

        List<ExplosionTask> batch = batcher.drainForTick();
        if (batch.isEmpty()) return;

        if (scheduler != null && scheduler.isEnabled()) {
            scheduler.submit(() -> processor.processBatch(batch), PrioritizedTaskScheduler.PRIORITY_EXPLOSION_NEAR);
        } else {
            processor.processBatch(batch);
        }
    }

    /**
     * 客户端 tick 回调，驱动粒子分帧处理。
     */
    public void onClientTick() {
        if (!enabled.get()) return;
        // 客户端仅处理粒子限流相关的分帧，物理计算由服务端处理
        List<ExplosionTask> batch = batcher.drainForFrame(8, 20);
        if (!batch.isEmpty()) {
            processor.processBatch(batch);
        }
    }

    public void reloadConfig() {
        ExplosionConfig config = ExplosionConfig.getInstance();
        enabled.set(config.isEnabled());
        batcher.setMaxPerFrame(config.getMaxPerFrame());
        batcher.setMaxPerTick(config.getMaxPerTick());
        particleLimiter.setLevel(config.getParticleLevelEnum());
        blockImpact.setSkipFarBlockImpact(config.isSkipFarBlockImpact());
        blockImpact.setFarBlockDistance(config.getFarBlockDistance());
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public ExplosionBatcher getBatcher() { return batcher; }
    public AsyncExplosionProcessor getProcessor() { return processor; }
    public ExplosionParticleLimiter getParticleLimiter() { return particleLimiter; }
    public ExplosionBlockImpact getBlockImpact() { return blockImpact; }

    public int getPendingExplosions() {
        return batcher.pendingCount();
    }

    public void shutdown() {
        batcher.clear();
        processor.resetStats();
        particleLimiter.resetStats();
        CoreSplitMod.LOGGER.info("[CoreSplit] ExplosionOptimizer shutdown");
    }
}
