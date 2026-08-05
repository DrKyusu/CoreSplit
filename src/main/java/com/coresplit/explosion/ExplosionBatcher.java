package com.coresplit.explosion;

import com.coresplit.CoreSplitMod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 爆炸分帧批处理器。
 *
 * <p>核心策略：每帧从优先级队列中取出爆炸任务，受双重约束：
 * <ol>
 *   <li>数量约束：每帧最多处理 {@code maxCount} 个</li>
 *   <li>时间约束：处理耗时不超过 {@code budgetMs} 毫秒</li>
 * </ol>
 * 超出约束的爆炸推延到下一帧，确保单帧不会因爆炸处理导致卡顿。
 */
public class ExplosionBatcher {

    /** 待处理队列容量上限，防止爆炸提交速率持续高于消费速率时无界增长导致 OOM。 */
    private static final int MAX_PENDING_CAPACITY = 50_000;

    private final PriorityBlockingQueue<ExplosionTask> pendingQueue = new PriorityBlockingQueue<>();
    private final AtomicInteger totalSubmitted = new AtomicInteger(0);
    private final AtomicInteger totalProcessed = new AtomicInteger(0);
    private final AtomicInteger totalDeferred = new AtomicInteger(0);
    private final AtomicInteger totalRejected = new AtomicInteger(0);

    private volatile int maxPerFrame;
    private volatile int maxPerTick;

    public ExplosionBatcher(int maxPerFrame, int maxPerTick) {
        this.maxPerFrame = Math.max(1, maxPerFrame);
        this.maxPerTick = Math.max(1, maxPerTick);
    }

    /**
     * 提交一个爆炸任务到待处理队列。
     *
     * <p>修复BUG: 原实现无界增长，20000 TNT 连锁爆炸时若提交速率高于消费速率
     * （maxPerTick 默认 200/tick），队列可累积数万任务，持续高负载下最终 OOM。
     * 现加入容量上限，超限拒绝入队（原版爆炸逻辑仍由 Mixin 正常执行，仅跳过冗余计算）。
     */
    public boolean offer(ExplosionTask task) {
        if (task == null) return false;
        if (pendingQueue.size() >= MAX_PENDING_CAPACITY) {
            totalRejected.incrementAndGet();
            return false;
        }
        pendingQueue.offer(task);
        totalSubmitted.incrementAndGet();
        return true;
    }

    /**
     * 取出本帧应处理的爆炸任务，受时间预算和数量双重约束。
     *
     * @param budgetMs 时间预算（毫秒）
     * @param maxCount 数量上限（受 maxPerFrame 钳制）
     * @return 本帧处理的任务列表
     */
    public List<ExplosionTask> drainForFrame(long budgetMs, int maxCount) {
        int limit = Math.min(maxCount, maxPerFrame);
        List<ExplosionTask> batch = new ArrayList<>(limit);
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;

        while (batch.size() < limit) {
            if (System.nanoTime() >= deadline && !batch.isEmpty()) {
                // 时间预算耗尽且已有任务，停止本帧
                break;
            }
            ExplosionTask task = pendingQueue.poll();
            if (task == null) break;
            batch.add(task);
        }

        totalProcessed.addAndGet(batch.size());
        int remaining = pendingQueue.size();
        if (remaining > 0) {
            totalDeferred.addAndGet(remaining);
        }
        return batch;
    }

    /**
     * 取出本 tick 应处理的爆炸任务（受 maxPerTick 约束）。
     */
    public List<ExplosionTask> drainForTick() {
        int limit = maxPerTick;
        List<ExplosionTask> batch = new ArrayList<>(Math.min(limit, pendingQueue.size()));
        while (batch.size() < limit) {
            ExplosionTask task = pendingQueue.poll();
            if (task == null) break;
            batch.add(task);
        }
        totalProcessed.addAndGet(batch.size());
        return batch;
    }

    public int pendingCount() {
        return pendingQueue.size();
    }

    public void setMaxPerFrame(int max) {
        this.maxPerFrame = Math.max(1, max);
    }

    public void setMaxPerTick(int max) {
        this.maxPerTick = Math.max(1, max);
    }

    public int getMaxPerFrame() { return maxPerFrame; }
    public int getMaxPerTick() { return maxPerTick; }

    public int getTotalSubmitted() { return totalSubmitted.get(); }
    public int getTotalProcessed() { return totalProcessed.get(); }
    public int getTotalDeferred() { return totalDeferred.get(); }
    public int getTotalRejected() { return totalRejected.get(); }

    public void clear() {
        pendingQueue.clear();
        CoreSplitMod.LOGGER.info("[CoreSplit] ExplosionBatcher cleared, pending was reset");
    }
}
