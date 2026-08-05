package com.coresplit.monitoring;

import com.coresplit.CoreSplitMod;

/**
 * 性能瓶颈分析器。
 *
 * <p>根据 {@link PerformanceMonitor} 收集的指标历史，分析当前性能瓶颈类型：
 * <ul>
 *   <li>{@link BottleneckType#CPU_BOUND} - MSPT 高、FPS 低，CPU 占用高</li>
 *   <li>{@link BottleneckType#GPU_BOUND} - FPS 低但 MSPT 正常，渲染线程饱和</li>
 *   <li>{@link BottleneckType#MEMORY_BOUND} - 内存占用高、GC 频繁</li>
 *   <li>{@link BottleneckType#ENTITY_BOUND} - 实体数量大、AI tick 慢</li>
 *   <li>{@link BottleneckType#NONE} - 无明显瓶颈</li>
 * </ul>
 */
public class BottleneckAnalyzer {

    public enum BottleneckType {
        NONE("No significant bottleneck detected"),
        CPU_BOUND("CPU bound: high MSPT and CPU usage, reduce simulation load"),
        GPU_BOUND("GPU bound: low FPS with acceptable MSPT, reduce render distance/particles"),
        MEMORY_BOUND("Memory bound: high memory usage and GC frequency"),
        ENTITY_BOUND("Entity bound: high entity count causing AI tick overhead");

        public final String description;

        BottleneckType(String description) {
            this.description = description;
        }
    }

    // 瓶颈判定阈值常量集中化
    private static final float CPU_BOUND_MSPT_THRESHOLD = 50f;
    private static final float CPU_BOUND_CPU_THRESHOLD = 80f;
    private static final float GPU_BOUND_FPS_THRESHOLD = 30f;
    private static final float GPU_BOUND_MSPT_OK_THRESHOLD = 40f;
    private static final float MEMORY_BOUND_PERCENT_THRESHOLD = 85f;
    private static final float MEMORY_BOUND_GC_THRESHOLD = 5f;
    private static final int ENTITY_BOUND_COUNT_THRESHOLD = 2000;

    private volatile BottleneckType currentBottleneck = BottleneckType.NONE;
    private volatile String analysisDetail = "";

    /**
     * 执行瓶颈分析。
     *
     * @param monitor 性能监控器实例
     * @return 当前瓶颈类型
     */
    public BottleneckType analyze(PerformanceMonitor monitor) {
        if (monitor == null) {
            currentBottleneck = BottleneckType.NONE;
            analysisDetail = "PerformanceMonitor unavailable";
            return currentBottleneck;
        }

        float mspt = monitor.getMetricValue(PerformanceMonitor.MetricType.MSPT);
        float fps = monitor.getMetricValue(PerformanceMonitor.MetricType.FPS);
        float cpu = monitor.getMetricValue(PerformanceMonitor.MetricType.CPU_USAGE);
        float gcCount = monitor.getMetricValue(PerformanceMonitor.MetricType.GC_COUNT);
        int entityCount = (int) monitor.getMetricValue(PerformanceMonitor.MetricType.ENTITY_COUNT);

        Runtime runtime = Runtime.getRuntime();
        float usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024f * 1024f);
        float maxMem = runtime.maxMemory() / (1024f * 1024f);
        float memPercent = maxMem > 0 ? (usedMem / maxMem) * 100 : 0;

        // 按优先级判定：内存 > CPU > GPU > 实体
        if (memPercent > MEMORY_BOUND_PERCENT_THRESHOLD || gcCount > MEMORY_BOUND_GC_THRESHOLD) {
            currentBottleneck = BottleneckType.MEMORY_BOUND;
            analysisDetail = String.format("Memory: %.0f%% (%.0fMB/%.0fMB), GC: %.1f/s",
                    memPercent, usedMem, maxMem, gcCount);
        } else if (mspt > CPU_BOUND_MSPT_THRESHOLD && cpu > CPU_BOUND_CPU_THRESHOLD) {
            currentBottleneck = BottleneckType.CPU_BOUND;
            analysisDetail = String.format("MSPT: %.1fms, CPU: %.1f%%", mspt, cpu);
        } else if (fps < GPU_BOUND_FPS_THRESHOLD && mspt < GPU_BOUND_MSPT_OK_THRESHOLD) {
            currentBottleneck = BottleneckType.GPU_BOUND;
            analysisDetail = String.format("FPS: %.1f, MSPT: %.1fms (render thread saturated)", fps, mspt);
        } else if (entityCount > ENTITY_BOUND_COUNT_THRESHOLD) {
            currentBottleneck = BottleneckType.ENTITY_BOUND;
            analysisDetail = String.format("Entities: %d (consider enabling AI throttle)", entityCount);
        } else {
            currentBottleneck = BottleneckType.NONE;
            analysisDetail = String.format("FPS: %.1f, MSPT: %.1fms, CPU: %.1f%%, Mem: %.0f%%, Entities: %d",
                    fps, mspt, cpu, memPercent, entityCount);
        }

        return currentBottleneck;
    }

    public BottleneckType getCurrentBottleneck() {
        return currentBottleneck;
    }

    public String getAnalysisDetail() {
        return analysisDetail;
    }

    /**
     * 生成瓶颈分析报告段，追加到性能报告末尾。
     */
    public String generateReportSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Bottleneck Analysis ===\n");
        sb.append(String.format("Type: %s\n", currentBottleneck.name()));
        sb.append(String.format("Detail: %s\n", analysisDetail));
        if (currentBottleneck != BottleneckType.NONE) {
            sb.append(String.format("Suggestion: %s\n", currentBottleneck.description));
        }
        return sb.toString();
    }

    /**
     * 记录瓶颈分析到日志。
     */
    public void logAnalysis() {
        if (currentBottleneck != BottleneckType.NONE) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Bottleneck: {} - {}", currentBottleneck.name(), analysisDetail);
        }
    }
}
