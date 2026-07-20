package com.coresplit.pipeline;

import com.coresplit.CoreSplitMod;
import com.coresplit.config.CoreSplitConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick 流水线编排器。
 *
 * 将单维度的 Tick 分解为有序阶段（参考 Sodium 的生产者-消费者模型 (citation:7)），
 * 每个阶段可独立优化（串行/并行），阶段间通过 Barrier 同步。
 *
 * 当前阶段划分：
 *   1. chunk-mgmt    区块加载/卸载
 *   2. random-ticks   随机方块 Tick
 *   3. block-entities 方块实体 Tick
 *   4. entities       实体 Tick
 *   5. environment    环境 Tick
 *   6. flush          数据刷新
 *
 * 每个阶段的耗时被记录用于性能分析。
 */
public class TickPipeline {

    private final CoreSplitConfig config;
    private final List<PhaseRecord> history = new CopyOnWriteArrayList<>();
    private final int maxHistory = 200; // 保留最近 200 个 Tick 的记录

    public TickPipeline(CoreSplitConfig config) {
        this.config = config;
    }

    /**
     * 执行一个阶段，记录耗时。
     */
    public void executePhase(String phaseName, Runnable action) {
        long start = System.nanoTime();
        try {
            action.run();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Phase '{}' threw exception", phaseName, e);
        } finally {
            long elapsed = System.nanoTime() - start;
            recordPhase(phaseName, elapsed);
        }
    }

    private void recordPhase(String name, long nanos) {
        if (!config.isMonitoringEnabled()) return;

        history.add(new PhaseRecord(name, nanos));
        if (history.size() > maxHistory) {
            history.subList(0, history.size() - maxHistory).clear();
        }
    }

    /**
     * 获取各阶段的平均耗时（毫秒）。
     */
    public java.util.Map<String, Double> getAveragePhaseTimings() {
        java.util.Map<String, List<Long>> grouped = new java.util.HashMap<>();
        for (PhaseRecord record : history) {
            grouped.computeIfAbsent(record.name, k -> new ArrayList<>()).add(record.nanos);
        }

        java.util.Map<String, Double> averages = new java.util.HashMap<>();
        for (var entry : grouped.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0.0) / 1_000_000.0; // 转换为毫秒
            averages.put(entry.getKey(), avg);
        }
        return averages;
    }

    private record PhaseRecord(String name, long nanos) {}
}