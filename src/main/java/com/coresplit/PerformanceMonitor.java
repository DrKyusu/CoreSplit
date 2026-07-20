package com.coresplit.metrics;

import com.coresplit.config.CoreSplitConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能监控模块。
 * 追踪各维度的 Tick 耗时、TPS、线程利用率。
 */
public class PerformanceMonitor {

    private final CoreSplitConfig config;

    // 全局指标
    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong totalDimensionTickNanos = new AtomicLong(0);
    private final AtomicLong totalDimensionsProcessed = new AtomicLong(0);

    // 每维度指标
    private final Map<String, DimensionMetrics> perDimension = new ConcurrentHashMap<>();

    // TPS 计算
    private final long[] recentTickTimes = new long[100];
    private int tickTimeIndex = 0;

    public PerformanceMonitor(CoreSplitConfig config) {
        this.config = config;
    }

    /**
     * 记录一个服务器 Tick 的维度级耗时。
     */
    public void recordDimensionTick(long nanos, int dimensionCount) {
        totalTicks.incrementAndGet();
        totalDimensionTickNanos.addAndGet(nanos);
        totalDimensionsProcessed.addAndGet(dimensionCount);

        synchronized (recentTickTimes) {
            recentTickTimes[tickTimeIndex % recentTickTimes.length] = nanos;
            tickTimeIndex++;
        }

        if (config.isLogPerTickMetrics()) {
            double ms = nanos / 1_000_000.0;
            if (ms > 50) {
                // 超过 50ms 的 Tick 值得记录
                System.out.printf("[CoreSplit] Slow tick: %.1fms (%d dimensions)%n", ms, dimensionCount);
            }
        }
    }

    /**
     * 记录单个维度的详细耗时。
     */
    public void recordDimensionDetail(String dimensionKey, long nanos) {
        perDimension.computeIfAbsent(dimensionKey, k -> new DimensionMetrics())
                .record(nanos);
    }

    /**
     * 计算当前 TPS。
     */
    public double getCurrentTPS() {
        synchronized (recentTickTimes) {
            int count = Math.min(tickTimeIndex, recentTickTimes.length);
            if (count < 2) return 20.0;

            long sum = 0;
            for (int i = 0; i < count; i++) {
                sum += recentTickTimes[i];
            }
            double avgNanos = (double) sum / count;
            double avgMs = avgNanos / 1_000_000.0;
            return Math.min(20.0, 1000.0 / avgMs);
        }
    }

    /**
     * 获取 MSPT (毫秒每 Tick)。
     */
    public double getCurrentMSPT() {
        synchronized (recentTickTimes) {
            int count = Math.min(tickTimeIndex, recentTickTimes.length);
            if (count == 0) return 0;

            long sum = 0;
            for (int i = 0; i < count; i++) {
                sum += recentTickTimes[i];
            }
            return ((double) sum / count) / 1_000_000.0;
        }
    }

    /**
     * 打印摘要到日志。
     */
    public void dumpSummary() {
        double avgMs = totalTicks.get() > 0
                ? (totalDimensionTickNanos.get() / 1_000_000.0) / totalTicks.get()
                : 0;
        double avgDims = totalTicks.get() > 0
                ? (double) totalDimensionsProcessed.get() / totalTicks.get()
                : 0;

        System.out.println("═══════════════════════════════════════");
        System.out.println("       CoreSplit Performance Summary   ");
        System.out.println("═══════════════════════════════════════");
        System.out.printf("  Total Ticks:            %d%n", totalTicks.get());
        System.out.printf("  Avg Tick Time:          %.2f ms%n", avgMs);
        System.out.printf("  Avg Dims per Tick:      %.1f%n", avgDims);
        System.out.printf("  Final TPS:              %.1f%n", getCurrentTPS());
        System.out.printf("  Final MSPT:             %.2f ms%n", getCurrentMSPT());
        System.out.println("───────────────────────────────────────");

        for (Map.Entry<String, DimensionMetrics> entry : perDimension.entrySet()) {
            DimensionMetrics m = entry.getValue();
            System.out.printf("  [%s] avg=%.2fms, max=%.2fms, ticks=%d%n",
                    entry.getKey(), m.getAverageMs(), m.getMaxMs(), m.getTickCount());
        }
        System.out.println("═══════════════════════════════════════");
    }

    // ─── Getters ───
    public Map<String, DimensionMetrics> getPerDimensionMetrics() {
        return Map.copyOf(perDimension);
    }
    public long getTotalTicks() { return totalTicks.get(); }

    /**
     * 单维度指标。
     */
    public static class DimensionMetrics {
        private final AtomicLong totalNanos = new AtomicLong(0);
        private final AtomicLong maxNanos = new AtomicLong(0);
        private final AtomicLong tickCount = new AtomicLong(0);

        void record(long nanos) {
            totalNanos.addAndGet(nanos);
            tickCount.incrementAndGet();
            // CAS 更新最大值
            long current;
            while ((current = maxNanos.get()) < nanos) {
                if (maxNanos.compareAndSet(current, nanos)) break;
            }
        }

        public double getAverageMs() {
            long count = tickCount.get();
            return count > 0 ? (totalNanos.get() / 1_000_000.0) / count : 0;
        }

        public double getMaxMs() {
            return maxNanos.get() / 1_000_000.0;
        }

        public long getTickCount() {
            return tickCount.get();
        }
    }
}