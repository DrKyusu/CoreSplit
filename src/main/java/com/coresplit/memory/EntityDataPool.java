package com.coresplit.memory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 通用对象池，减少高频对象创建的 GC 压力。
 *
 * <p>使用 {@link ConcurrentLinkedQueue} 作为栈式池，线程安全。
 * 适用场景：实体 NBT 数据对象、动画状态对象等高频创建/销毁的对象。
 *
 * <p>使用方式：
 * <pre>{@code
 * EntityDataPool<StringBuilder> pool = new EntityDataPool<>(256);
 * StringBuilder sb = pool.acquire(StringBuilder::new);
 * try {
 *     // 使用 sb
 * } finally {
 *     pool.release(sb, StringBuilder::setLength);
 * }
 * }</pre>
 *
 * @param <T> 池化对象类型
 */
public class EntityDataPool<T> {

    private static final int MAX_POOL_SIZE_DEFAULT = 1024;

    private final ConcurrentLinkedQueue<T> pool = new ConcurrentLinkedQueue<>();
    private final int maxPoolSize;
    private final AtomicInteger currentSize = new AtomicInteger(0);
    private final AtomicLong acquireCount = new AtomicLong(0);
    private final AtomicLong releaseCount = new AtomicLong(0);
    private final AtomicLong createCount = new AtomicLong(0);
    private final AtomicLong reuseCount = new AtomicLong(0);

    public EntityDataPool(int maxPoolSize) {
        this.maxPoolSize = Math.max(16, maxPoolSize);
    }

    public EntityDataPool() {
        this(MAX_POOL_SIZE_DEFAULT);
    }

    /**
     * 从池中借用对象，池为空时用 factory 创建。
     */
    public T acquire(Supplier<T> factory) {
        acquireCount.incrementAndGet();
        T obj = pool.poll();
        if (obj != null) {
            currentSize.decrementAndGet();
            reuseCount.incrementAndGet();
            return obj;
        }
        createCount.incrementAndGet();
        return factory.get();
    }

    /**
     * 归还对象到池中，归还会调用 resetter 重置对象状态。
     *
     * @param obj 归还的对象
     * @param resetter 重置函数（如 StringBuilder::setLength 传入 0）
     */
    public void release(T obj, Consumer<T> resetter) {
        if (obj == null) return;
        releaseCount.incrementAndGet();

        // 池满则丢弃，让 GC 回收
        if (currentSize.get() >= maxPoolSize) {
            return;
        }

        try {
            if (resetter != null) {
                resetter.accept(obj);
            }
        } catch (Exception e) {
            // resetter 失败则不回收该对象
            return;
        }

        if (currentSize.incrementAndGet() <= maxPoolSize) {
            pool.offer(obj);
        } else {
            currentSize.decrementAndGet();
        }
    }

    public int getPoolSize() {
        return currentSize.get();
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public long getAcquireCount() { return acquireCount.get(); }
    public long getReleaseCount() { return releaseCount.get(); }
    public long getCreateCount() { return createCount.get(); }
    public long getReuseCount() { return reuseCount.get(); }

    /**
     * 复用率（衡量池效果）。
     */
    public float reuseRate() {
        long acquires = acquireCount.get();
        return acquires > 0 ? (float) reuseCount.get() / acquires : 0f;
    }

    public void clear() {
        pool.clear();
        currentSize.set(0);
    }
}
