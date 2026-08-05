package com.coresplit.memory;

import com.coresplit.CoreSplitMod;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ByteBuffer 对象池，按容量分桶管理。
 *
 * <p>场景：区块 IO、网络序列化等高频分配 ByteBuffer 的场景。
 * 直接分配/释放 ByteBuffer 会增加 GC 压力和内存碎片，池化复用可显著降低开销。
 *
 * <p>策略：按容量向上取整到最近的 2 的幂分桶（512/1024/2048/.../65536），
 * 同容量桶内用 {@link ConcurrentLinkedQueue} 管理空闲 buffer。
 */
public class ByteBufferPool {

    private static final int MIN_CAPACITY = 512;
    private static final int MAX_CAPACITY = 65536;

    // 按容量分桶：capacity -> 空闲 buffer 队列
    private final Map<Integer, ConcurrentLinkedQueue<ByteBuffer>> buckets = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    private final AtomicLong totalAcquired = new AtomicLong(0);
    private final AtomicLong totalReused = new AtomicLong(0);
    private final AtomicLong totalReleased = new AtomicLong(0);
    private final AtomicLong totalAllocated = new AtomicLong(0);

    public ByteBufferPool(boolean enabled) {
        this.enabled.set(enabled);
    }

    /**
     * 借用指定容量的 ByteBuffer。容量会向上取整到最近的 2 的幂。
     */
    public ByteBuffer acquire(int requestedCapacity) {
        totalAcquired.incrementAndGet();
        if (!enabled.get()) {
            return ByteBuffer.allocate(Math.max(1, requestedCapacity));
        }
        int bucketCapacity = roundUpToPowerOfTwo(requestedCapacity);
        ConcurrentLinkedQueue<ByteBuffer> bucket = buckets.get(bucketCapacity);
        if (bucket != null) {
            ByteBuffer buffer = bucket.poll();
            if (buffer != null) {
                buffer.clear();
                totalReused.incrementAndGet();
                return buffer;
            }
        }
        totalAllocated.incrementAndGet();
        return ByteBuffer.allocate(bucketCapacity);
    }

    /**
     * 归还 ByteBuffer 到池中。
     */
    public void release(ByteBuffer buffer) {
        if (buffer == null || !enabled.get()) return;
        totalReleased.incrementAndGet();

        int capacity = buffer.capacity();
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            // 超出池管理范围的 buffer 不回收
            return;
        }

        int bucketCapacity = roundUpToPowerOfTwo(capacity);
        ConcurrentLinkedQueue<ByteBuffer> bucket = buckets.computeIfAbsent(
                bucketCapacity, k -> new ConcurrentLinkedQueue<>());

        // 每桶最多保留 32 个，防止内存占用无限增长
        if (bucket.size() < 32) {
            buffer.clear();
            bucket.offer(buffer);
        }
    }

    /**
     * 将容量向上取整到最近的 2 的幂（在 MIN/MAX 范围内）。
     */
    private static int roundUpToPowerOfTwo(int capacity) {
        if (capacity <= MIN_CAPACITY) return MIN_CAPACITY;
        if (capacity > MAX_CAPACITY) return MAX_CAPACITY;
        int power = MIN_CAPACITY;
        while (power < capacity) {
            power <<= 1;
        }
        return power;
    }

    public int getTotalPooledBuffers() {
        int total = 0;
        for (ConcurrentLinkedQueue<ByteBuffer> bucket : buckets.values()) {
            total += bucket.size();
        }
        return total;
    }

    public long getTotalPooledBytes() {
        long total = 0;
        for (Map.Entry<Integer, ConcurrentLinkedQueue<ByteBuffer>> entry : buckets.entrySet()) {
            total += (long) entry.getKey() * entry.getValue().size();
        }
        return total;
    }

    public float reuseRate() {
        long acquires = totalAcquired.get();
        return acquires > 0 ? (float) totalReused.get() / acquires : 0f;
    }

    public long getTotalAcquired() { return totalAcquired.get(); }
    public long getTotalReused() { return totalReused.get(); }
    public long getTotalReleased() { return totalReleased.get(); }
    public long getTotalAllocated() { return totalAllocated.get(); }

    public boolean isEnabled() { return enabled.get(); }

    public void setEnabled(boolean enabled) {
        boolean wasEnabled = this.enabled.getAndSet(enabled);
        if (wasEnabled && !enabled) {
            clear();
        }
    }

    public void clear() {
        buckets.clear();
        CoreSplitMod.LOGGER.info("[CoreSplit] ByteBufferPool cleared");
    }
}
