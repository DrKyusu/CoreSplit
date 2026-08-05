package com.coresplit.memory;

import com.coresplit.CoreSplitMod;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 资源周期性驱逐器。
 *
 * <p>按配置的间隔周期扫描纹理缓存、模型缓存等资源，触发 LRU 驱逐，
 * 在内存紧张时主动释放资源。
 *
 * <p>由 {@link MemoryOptimizer} 在 tick 回调中驱动。
 */
public class ResourceEvictor {

    private volatile long evictIntervalMs;
    private volatile int textureCacheTarget;
    private volatile long lastEvictTimeMs = 0;
    private final AtomicLong totalEvictRuns = new AtomicLong(0);
    private final AtomicLong totalItemsEvicted = new AtomicLong(0);
    private final AtomicBoolean aggressiveMode = new AtomicBoolean(false);

    // 驱逐回调：由 MemoryOptimizer 注入实际的缓存清理逻辑
    private volatile EvictionCallback textureCacheCallback;
    private volatile EvictionCallback modelCacheCallback;

    @FunctionalInterface
    public interface EvictionCallback {
        int evictTo(int targetSize);
    }

    public ResourceEvictor(long evictIntervalMs, int textureCacheTarget) {
        this.evictIntervalMs = Math.max(1000, evictIntervalMs);
        this.textureCacheTarget = Math.max(64, textureCacheTarget);
    }

    /**
     * tick 回调，检查是否到达驱逐时机。
     */
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now - lastEvictTimeMs < evictIntervalMs) return;
        lastEvictTimeMs = now;
        evict();
    }

    private void evict() {
        totalEvictRuns.incrementAndGet();
        int target = aggressiveMode.get() ? textureCacheTarget / 2 : textureCacheTarget;

        if (textureCacheCallback != null) {
            try {
                int evicted = textureCacheCallback.evictTo(target);
                totalItemsEvicted.addAndGet(evicted);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Texture cache eviction failed", e);
            }
        }
        if (modelCacheCallback != null) {
            try {
                int evicted = modelCacheCallback.evictTo(target);
                totalItemsEvicted.addAndGet(evicted);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Model cache eviction failed", e);
            }
        }
    }

    public void setTextureCacheCallback(EvictionCallback callback) {
        this.textureCacheCallback = callback;
    }

    public void setModelCacheCallback(EvictionCallback callback) {
        this.modelCacheCallback = callback;
    }

    public void setAggressiveMode(boolean aggressive) {
        this.aggressiveMode.set(aggressive);
    }

    public boolean isAggressiveMode() {
        return aggressiveMode.get();
    }

    public void setTextureCacheTarget(int target) {
        this.textureCacheTarget = Math.max(64, target);
    }

    public void setEvictIntervalMs(long intervalMs) {
        this.evictIntervalMs = Math.max(1000, intervalMs);
    }

    public long getEvictIntervalMs() {
        return evictIntervalMs;
    }

    public long getTotalEvictRuns() { return totalEvictRuns.get(); }
    public long getTotalItemsEvicted() { return totalItemsEvicted.get(); }
}
