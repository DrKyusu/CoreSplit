package com.coresplit.ai;

import com.coresplit.CoreSplitMod;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 路径缓存，仿 {@link com.coresplit.texture.TextureCache} 的 LRU + TTL 设计。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储缓存项，{@link ConcurrentSkipListMap} 作为 LRU 索引
 * （O(log n) 驱逐，避免 O(n) 扫描）。
 *
 * <p>TTL 过期 + LRU 容量驱逐双重淘汰策略：
 * <ul>
 *   <li>访问时检查 TTL，过期则移除</li>
 *   <li>新增时检查容量，超限则驱逐最久未访问项</li>
 * </ul>
 */
public class PathCache {

    private final ConcurrentHashMap<PathKey, CachedPath> cache = new ConcurrentHashMap<>();
    // LRU 索引：lastAccessTime -> key（相同时间用 seq 区分）
    private final ConcurrentSkipListMap<Long, PathKey> lruIndex = new ConcurrentSkipListMap<>();
    private final AtomicLong seqCounter = new AtomicLong(0);

    private final long ttlMs;
    private final int maxSize;

    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong evictCount = new AtomicLong(0);

    public PathCache(long ttlMs, int maxSize) {
        this.ttlMs = Math.max(1000, ttlMs);
        this.maxSize = Math.max(16, maxSize);
    }

    /**
     * 尝试获取缓存的路径。
     *
     * @param key 路径键
     * @return 缓存的路径对象，未命中或已过期返回 null
     */
    public Object tryGet(PathKey key) {
        if (key == null) {
            missCount.incrementAndGet();
            return null;
        }
        CachedPath entry = cache.get(key);
        if (entry == null) {
            missCount.incrementAndGet();
            return null;
        }
        // TTL 检查
        if (System.currentTimeMillis() - entry.createTime > ttlMs) {
            removeEntry(key, entry);
            missCount.incrementAndGet();
            return null;
        }
        // 更新访问时间（LRU）
        touchAccess(key, entry);
        hitCount.incrementAndGet();
        return entry.path;
    }

    /**
     * 存入路径。
     */
    public void put(PathKey key, Object path) {
        if (key == null || path == null) return;

        CachedPath entry = new CachedPath(path, System.currentTimeMillis());
        CachedPath old = cache.put(key, entry);
        if (old != null) {
            lruIndex.remove(old.lruKey);
        }
        entry.lruKey = makeLruKey();
        lruIndex.put(entry.lruKey, key);

        // 容量驱逐
        while (cache.size() > maxSize) {
            evictOldest();
        }
    }

    /**
     * 失效经过指定 chunk 的所有缓存路径（方块变化时调用）。
     */
    public void invalidateFor(int chunkX, int chunkZ) {
        Iterator<Map.Entry<PathKey, CachedPath>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PathKey, CachedPath> e = it.next();
            if (e.getKey().passesThrough(chunkX, chunkZ)) {
                removeEntry(e.getKey(), e.getValue());
            }
        }
    }

    private void touchAccess(PathKey key, CachedPath entry) {
        lruIndex.remove(entry.lruKey);
        entry.lruKey = makeLruKey();
        lruIndex.put(entry.lruKey, key);
    }

    private void evictOldest() {
        Map.Entry<Long, PathKey> oldest = lruIndex.pollFirstEntry();
        if (oldest == null) return;
        PathKey key = oldest.getValue();
        CachedPath entry = cache.get(key);
        if (entry != null) {
            cache.remove(key, entry);
            evictCount.incrementAndGet();
        }
    }

    private void removeEntry(PathKey key, CachedPath entry) {
        if (cache.remove(key, entry)) {
            lruIndex.remove(entry.lruKey);
        }
    }

    private long makeLruKey() {
        // 修复BUG: 原实现 (System.nanoTime() << 20) | (seq & 0xFFFFF) 在 nanoTime > 2^43
        //（约 2.4 小时运行时长后）会因左移溢出跨越 long 符号边界，导致 LRU key 排序错乱：
        // 后插入的项可能得到更小的（负）key，被自身驱逐循环优先淘汰，而最旧的项反而残留。
        // 改用纯单调递增的 AtomicLong 作为 key（与 TextureCache 一致）：
        // 既保证 put 顺序与 key 大小单调对应，又无溢出/符号翻转风险，touchAccess 重分配更大值即实现 LRU。
        return seqCounter.getAndIncrement();
    }

    public float hitRate() {
        long hits = hitCount.get();
        long total = hits + missCount.get();
        return total > 0 ? (float) hits / total : 0f;
    }

    public int size() {
        return cache.size();
    }

    public long getHitCount() { return hitCount.get(); }
    public long getMissCount() { return missCount.get(); }
    public long getEvictCount() { return evictCount.get(); }

    public void clear() {
        cache.clear();
        lruIndex.clear();
        hitCount.set(0);
        missCount.set(0);
        evictCount.set(0);
        CoreSplitMod.LOGGER.info("[CoreSplit] PathCache cleared");
    }

    private static class CachedPath {
        final Object path;
        final long createTime;
        volatile long lruKey;

        CachedPath(Object path, long createTime) {
            this.path = path;
            this.createTime = createTime;
        }
    }
}
