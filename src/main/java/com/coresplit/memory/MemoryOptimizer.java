package com.coresplit.memory;

import com.coresplit.CoreSplitMod;
import com.coresplit.chunk.ChunkEngine;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存管理优化模块门面（单例）。
 *
 * <p>职责：
 * <ol>
 *   <li>管理 {@link EntityDataPool}（实体数据对象池）</li>
 *   <li>管理 {@link ByteBufferPool}（ByteBuffer 池）</li>
 *   <li>管理 {@link ResourceEvictor}（周期资源驱逐）</li>
 *   <li>管理 {@link EntityDataUnloader}（远实体数据卸载）</li>
 *   <li>管理 {@link HeapDefragmenter}（堆碎片监控）</li>
 *   <li>提供 {@link #aggressiveEvict()} 供 Governor 在质量降级时调用</li>
 * </ol>
 */
public class MemoryOptimizer {

    private static volatile MemoryOptimizer instance;

    private final EntityDataPool<Object> entityDataPool;
    private final ByteBufferPool byteBufferPool;
    private final ResourceEvictor resourceEvictor;
    private final EntityDataUnloader entityDataUnloader;
    private final HeapDefragmenter heapDefragmenter;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicLong memorySavedMb = new AtomicLong(0);

    private MemoryOptimizer() {
        MemoryConfig config = MemoryConfig.getInstance();
        this.entityDataPool = new EntityDataPool<>(config.getEntityDataPoolSize());
        this.byteBufferPool = new ByteBufferPool(config.isByteBufferPoolEnabled());
        this.resourceEvictor = new ResourceEvictor(config.getEvictIntervalMs(), config.getTextureCacheMax());
        this.entityDataUnloader = new EntityDataUnloader(
                ChunkEngine.getInstance().getEntityFilter(),
                config.getAggressiveEvictBelowMb());
        this.heapDefragmenter = new HeapDefragmenter();
        this.enabled.set(config.isEnabled());
    }

    public static MemoryOptimizer getInstance() {
        MemoryOptimizer result = instance;
        if (result == null) {
            synchronized (MemoryOptimizer.class) {
                result = instance;
                if (result == null) {
                    result = new MemoryOptimizer();
                    instance = result;
                }
            }
        }
        return result;
    }

    /**
     * tick 回调，驱动驱逐器和碎片监控。
     */
    public void onTick() {
        if (!enabled.get()) return;
        resourceEvictor.onTick();
        heapDefragmenter.onTick();

        // 光影内存优化器：光影激活时主动缩减缓存
        try {
            com.coresplit.compat.ShaderMemoryOptimizer.getInstance().onTick();
        } catch (Exception ignored) {}

        // 估算节省的内存：池化复用 + 驱逐释放
        long saved = byteBufferPool.getTotalPooledBytes() / (1024 * 1024)
                + resourceEvictor.getTotalItemsEvicted() / 256; // 粗略估算
        memorySavedMb.set(saved);
    }

    /**
     * 激进驱逐，由 Governor 在高质量降级时调用。
     */
    public void aggressiveEvict() {
        CoreSplitMod.LOGGER.info("[CoreSplit] MemoryOptimizer aggressive eviction triggered");
        resourceEvictor.setAggressiveMode(true);
        resourceEvictor.onTick();
    }

    /**
     * 恢复正常驱逐模式。
     */
    public void normalEvict() {
        resourceEvictor.setAggressiveMode(false);
    }

    public void reloadConfig() {
        MemoryConfig config = MemoryConfig.getInstance();
        enabled.set(config.isEnabled());
        resourceEvictor.setTextureCacheTarget(config.getTextureCacheMax());
    }

    public void applyConfiguration(boolean enabled, boolean byteBufferPoolEnabled,
                                   int textureCacheMax, int evictIntervalSeconds) {
        this.enabled.set(enabled);
        byteBufferPool.setEnabled(byteBufferPoolEnabled);
        resourceEvictor.setTextureCacheTarget(textureCacheMax);
        resourceEvictor.setEvictIntervalMs(evictIntervalSeconds * 1000L);
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public EntityDataPool<Object> getEntityDataPool() { return entityDataPool; }
    public ByteBufferPool getByteBufferPool() { return byteBufferPool; }
    public ResourceEvictor getResourceEvictor() { return resourceEvictor; }
    public EntityDataUnloader getEntityDataUnloader() { return entityDataUnloader; }
    public HeapDefragmenter getHeapDefragmenter() { return heapDefragmenter; }

    public long getMemorySavedMb() { return memorySavedMb.get(); }

    public void shutdown() {
        entityDataPool.clear();
        byteBufferPool.clear();
        CoreSplitMod.LOGGER.info("[CoreSplit] MemoryOptimizer shutdown");
    }
}
