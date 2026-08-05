package com.coresplit.ai;

import com.coresplit.CoreSplitMod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同类实体路径共享注册表。
 *
 * <p>场景：村民编队、龙群同源移动时，多个同类实体前往相近目的地，
 * 可共享同一条路径计算结果，避免重复寻路。
 *
 * <p>策略：以 {@link PathKey}（chunk 级别）为键，同 chunk 内同类型实体的路径共享。
 * 当一个实体计算出路径后注册到此处，其他实体先查询此处再决定是否自行寻路。
 */
public class SharedPathRegistry {

    private final ConcurrentHashMap<PathKey, Object> sharedPaths = new ConcurrentHashMap<>();
    private final long shareTtlMs;

    private final AtomicLong shareHits = new AtomicLong(0);
    private final AtomicLong shareMisses = new AtomicLong(0);
    private final AtomicLong registeredCount = new AtomicLong(0);

    public SharedPathRegistry(long shareTtlMs) {
        this.shareTtlMs = Math.max(5000, shareTtlMs);
    }

    /**
     * 尝试获取共享路径。
     */
    public Object tryGetShared(PathKey key) {
        if (key == null) {
            shareMisses.incrementAndGet();
            return null;
        }
        Object path = sharedPaths.get(key);
        if (path != null) {
            shareHits.incrementAndGet();
            return path;
        }
        shareMisses.incrementAndGet();
        return null;
    }

    /**
     * 注册一个可共享的路径。
     */
    public void registerShared(PathKey key, Object path) {
        if (key == null || path == null) return;
        sharedPaths.put(key, path);
        registeredCount.incrementAndGet();
    }

    /**
     * 清理过期的共享路径（由周期任务调用）。
     */
    public void cleanupExpired(long createTimeThreshold) {
        // 简化实现：ConcurrentHashMap 无法按值过滤，全量清理过期项
        // 实际由 PathCache 的 TTL 兜底，这里仅做容量控制
        if (sharedPaths.size() > 2048) {
            sharedPaths.clear();
            CoreSplitMod.LOGGER.debug("[CoreSplit] SharedPathRegistry cleared (size exceeded threshold)");
        }
    }

    public float shareHitRate() {
        long hits = shareHits.get();
        long total = hits + shareMisses.get();
        return total > 0 ? (float) hits / total : 0f;
    }

    public int size() {
        return sharedPaths.size();
    }

    public long getShareHits() { return shareHits.get(); }
    public long getShareMisses() { return shareMisses.get(); }
    public long getRegisteredCount() { return registeredCount.get(); }

    public void clear() {
        sharedPaths.clear();
        shareHits.set(0);
        shareMisses.set(0);
        registeredCount.set(0);
    }
}
