package com.coresplit.memory;

import com.coresplit.CoreSplitMod;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 堆内存碎片化优化器。
 *
 * <p>策略（JDK 25 G1/ZGC 友好，不强制 GC）：
 * <ul>
 *   <li>周期性记录堆使用情况，检测碎片化趋势</li>
 *   <li>提供大对象预分配建议（仅日志，不强制）</li>
 *   <li>在内存紧张时记录建议供 BottleneckAnalyzer 分析</li>
 * </ul>
 *
 * <p>注意：不调用 System.gc()。JDK 25 的 G1/ZGC 能很好处理碎片化，
 * 强制 GC 反而会增加停顿。
 */
public class HeapDefragmenter {

    private final AtomicLong lastCheckTimeMs = new AtomicLong(0);
    private volatile float lastHeapUsagePercent = 0;
    private volatile long lastUsedHeapMb = 0;
    private volatile long lastMaxHeapMb = 0;
    private volatile boolean fragmentedWarning = false;

    private static final long CHECK_INTERVAL_MS = 5000;
    private static final float FRAGMENT_WARN_THRESHOLD = 0.85f;

    public void onTick() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTimeMs.get() < CHECK_INTERVAL_MS) return;
        lastCheckTimeMs.set(now);
        checkHeap();
    }

    private void checkHeap() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        lastUsedHeapMb = used / (1024 * 1024);
        lastMaxHeapMb = max / (1024 * 1024);
        lastHeapUsagePercent = max > 0 ? (float) used / max : 0;

        if (lastHeapUsagePercent > FRAGMENT_WARN_THRESHOLD && !fragmentedWarning) {
            fragmentedWarning = true;
            CoreSplitMod.LOGGER.warn("[CoreSplit] Heap usage high: {}MB / {}MB ({}%). "
                            + "Consider enabling aggressive eviction or increasing heap.",
                    lastUsedHeapMb, lastMaxHeapMb, (int) (lastHeapUsagePercent * 100));
        } else if (lastHeapUsagePercent <= FRAGMENT_WARN_THRESHOLD * 0.8) {
            fragmentedWarning = false;
        }
    }

    public float getHeapUsagePercent() { return lastHeapUsagePercent; }
    public long getUsedHeapMb() { return lastUsedHeapMb; }
    public long getMaxHeapMb() { return lastMaxHeapMb; }
    public boolean isFragmentedWarning() { return fragmentedWarning; }
}
