package com.coresplit.chunk;

import com.coresplit.CoreSplitMod;
import com.coresplit.scheduler.HardwareDetector;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class ChunkEngineConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("coresplit_chunk.toml");

    private boolean parallelGenerationEnabled = true;
    private boolean asyncIOEnabled = true;
    private boolean concurrentLoadingEnabled = true;
    private boolean entityFilterEnabled = true;
    private boolean smoothTransferEnabled = true;

    // Defaults are derived from the hardware-aware detector:
    //  - generation threads: capped at recommendMemorySensitiveThreads() to not exceed CPU cache thrashing
    //  - io threads: recommendMixedThreads() / 2 since loading is mostly blocking I/O
    private int generationThreads = com.coresplit.scheduler.HardwareDetector.recommendMemorySensitiveThreads();
    private int ioThreads = Math.max(2, com.coresplit.scheduler.HardwareDetector.recommendMixedThreads() / 2);
    private int maxConcurrentLoads = 6;
    private int maxConcurrentGenerations = 4;

    private int noTickViewDistance = 4;
    private int noTickMaxConcurrentLoads = 3;

    private int entityPartitionSize = 16;
    private int maxEntitiesPerPartition = 100;

    private int taskPriorityBase = 100;
    private int priorityDistanceFactor = 5;

    private long maxTaskTimeMs = 50;
    private long taskYieldThresholdMs = 30;

    // 修复BUG: instance 未声明 volatile 且 getInstance 无同步，多线程并发调用时可能创建多个实例，
    // 导致配置不一致，改用 volatile + 双重检查锁定
    private static volatile ChunkEngineConfig instance;

    public static ChunkEngineConfig getInstance() {
        ChunkEngineConfig result = instance;
        if (result == null) {
            synchronized (ChunkEngineConfig.class) {
                result = instance;
                if (result == null) {
                    result = new ChunkEngineConfig();
                    result.load();
                    instance = result;
                }
            }
        }
        return result;
    }

    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try (FileConfig fc = FileConfig.of(CONFIG_PATH)) {
            fc.load();

            parallelGenerationEnabled = fc.getOrElse("parallel_generation.enabled", true);
            asyncIOEnabled = fc.getOrElse("async_io.enabled", true);
            concurrentLoadingEnabled = fc.getOrElse("concurrent_loading.enabled", true);
            entityFilterEnabled = fc.getOrElse("entity_filter.enabled", true);
            smoothTransferEnabled = fc.getOrElse("smooth_transfer.enabled", true);

            int hwGenThreads = HardwareDetector.recommendMemorySensitiveThreads();
            int hwIoThreads = Math.max(2, HardwareDetector.recommendMixedThreads() / 2);
            generationThreads = ((Number) fc.getOrElse("parallel_generation.threads", hwGenThreads)).intValue();
            ioThreads = ((Number) fc.getOrElse("async_io.threads", hwIoThreads)).intValue();
            maxConcurrentLoads = fc.getOrElse("concurrent_loading.max_concurrent", 6);
            maxConcurrentGenerations = fc.getOrElse("parallel_generation.max_concurrent", 4);

            noTickViewDistance = fc.getOrElse("no_tick.view_distance", 4);
            noTickMaxConcurrentLoads = fc.getOrElse("no_tick.max_concurrent_loads", 3);

            entityPartitionSize = fc.getOrElse("entity_filter.partition_size", 16);
            maxEntitiesPerPartition = fc.getOrElse("entity_filter.max_per_partition", 100);

            taskPriorityBase = fc.getOrElse("scheduling.priority_base", 100);
            priorityDistanceFactor = fc.getOrElse("scheduling.distance_factor", 5);

            maxTaskTimeMs = fc.getOrElse("scheduling.max_task_time_ms", 50);
            taskYieldThresholdMs = fc.getOrElse("scheduling.yield_threshold_ms", 30);

            validate();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load chunk engine config", e);
        }
    }

    public void save() {
        try {
            StringBuilder content = new StringBuilder();
            content.append("# CoreSplit Chunk Engine Configuration\n\n");

            content.append("[parallel_generation]\n");
            content.append("enabled = ").append(parallelGenerationEnabled).append("\n");
            content.append("threads = ").append(generationThreads).append("\n");
            content.append("max_concurrent = ").append(maxConcurrentGenerations).append("\n\n");

            content.append("[async_io]\n");
            content.append("enabled = ").append(asyncIOEnabled).append("\n");
            content.append("threads = ").append(ioThreads).append("\n\n");

            content.append("[concurrent_loading]\n");
            content.append("enabled = ").append(concurrentLoadingEnabled).append("\n");
            content.append("max_concurrent = ").append(maxConcurrentLoads).append("\n\n");

            content.append("[no_tick]\n");
            content.append("view_distance = ").append(noTickViewDistance).append("\n");
            content.append("max_concurrent_loads = ").append(noTickMaxConcurrentLoads).append("\n\n");

            content.append("[entity_filter]\n");
            content.append("enabled = ").append(entityFilterEnabled).append("\n");
            content.append("partition_size = ").append(entityPartitionSize).append("\n");
            content.append("max_per_partition = ").append(maxEntitiesPerPartition).append("\n\n");

            content.append("[scheduling]\n");
            content.append("priority_base = ").append(taskPriorityBase).append("\n");
            content.append("distance_factor = ").append(priorityDistanceFactor).append("\n");
            content.append("max_task_time_ms = ").append(maxTaskTimeMs).append("\n");
            content.append("yield_threshold_ms = ").append(taskYieldThresholdMs).append("\n");

            Files.writeString(CONFIG_PATH, content.toString());
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to save chunk engine config", e);
        }
    }

    private void validate() {
        int maxCores = HardwareDetector.getLogicalCores();

        // 修复BUG: 项目约束要求线程数量必须在 1-4 倍 CPU 核心数范围内，原代码上限为 maxCores，
        // 过于严格且与约束不符，改为上限 maxCores * 4
        int maxThreadBound = Math.max(1, maxCores * 4);
        generationThreads = Math.max(1, Math.min(maxThreadBound, generationThreads));
        ioThreads = Math.max(1, Math.min(maxThreadBound, ioThreads));
        maxConcurrentLoads = Math.max(1, Math.min(16, maxConcurrentLoads));
        maxConcurrentGenerations = Math.max(1, Math.min(8, maxConcurrentGenerations));

        noTickViewDistance = Math.max(0, Math.min(16, noTickViewDistance));
        noTickMaxConcurrentLoads = Math.max(1, Math.min(8, noTickMaxConcurrentLoads));

        entityPartitionSize = Math.max(8, Math.min(64, entityPartitionSize));
        maxEntitiesPerPartition = Math.max(10, Math.min(500, maxEntitiesPerPartition));

        taskPriorityBase = Math.max(10, Math.min(1000, taskPriorityBase));
        priorityDistanceFactor = Math.max(1, Math.min(50, priorityDistanceFactor));

        maxTaskTimeMs = Math.max(10, Math.min(500, maxTaskTimeMs));
        // 修复BUG: 原代码将 long 强转为 int 比较，虽然当前范围安全但易随范围调整而出错，
        // 改为 long 比较避免截断风险
        taskYieldThresholdMs = Math.max(5, Math.min(maxTaskTimeMs, taskYieldThresholdMs));
    }

    public boolean isParallelGenerationEnabled() { return parallelGenerationEnabled; }
    public boolean isAsyncIOEnabled() { return asyncIOEnabled; }
    public boolean isConcurrentLoadingEnabled() { return concurrentLoadingEnabled; }
    public boolean isEntityFilterEnabled() { return entityFilterEnabled; }
    public boolean isSmoothTransferEnabled() { return smoothTransferEnabled; }

    public int getGenerationThreads() { return generationThreads; }
    public int getIOThreads() { return ioThreads; }
    public int getMaxConcurrentLoads() { return maxConcurrentLoads; }
    public int getMaxConcurrentGenerations() { return maxConcurrentGenerations; }

    public int getNoTickViewDistance() { return noTickViewDistance; }
    public int getNoTickMaxConcurrentLoads() { return noTickMaxConcurrentLoads; }

    public int getEntityPartitionSize() { return entityPartitionSize; }
    public int getMaxEntitiesPerPartition() { return maxEntitiesPerPartition; }

    public int getTaskPriorityBase() { return taskPriorityBase; }
    public int getPriorityDistanceFactor() { return priorityDistanceFactor; }

    public long getMaxTaskTimeMs() { return maxTaskTimeMs; }
    public long getTaskYieldThresholdMs() { return taskYieldThresholdMs; }

    public void setParallelGenerationEnabled(boolean enabled) { parallelGenerationEnabled = enabled; save(); }
    public void setAsyncIOEnabled(boolean enabled) { asyncIOEnabled = enabled; save(); }
    public void setConcurrentLoadingEnabled(boolean enabled) { concurrentLoadingEnabled = enabled; save(); }
    public void setEntityFilterEnabled(boolean enabled) { entityFilterEnabled = enabled; save(); }
    public void setSmoothTransferEnabled(boolean enabled) { smoothTransferEnabled = enabled; save(); }

    public void setGenerationThreads(int threads) { generationThreads = threads; validate(); save(); }
    public void setIOThreads(int threads) { ioThreads = threads; validate(); save(); }
    public void setMaxConcurrentLoads(int max) { maxConcurrentLoads = max; validate(); save(); }
    public void setMaxConcurrentGenerations(int max) { maxConcurrentGenerations = max; validate(); save(); }

    public void setNoTickViewDistance(int distance) { noTickViewDistance = distance; validate(); save(); }
    public void setNoTickMaxConcurrentLoads(int max) { noTickMaxConcurrentLoads = max; validate(); save(); }

    public void setEntityPartitionSize(int size) { entityPartitionSize = size; validate(); save(); }
    public void setMaxEntitiesPerPartition(int max) { maxEntitiesPerPartition = max; validate(); save(); }

    public void setTaskPriorityBase(int base) { taskPriorityBase = base; validate(); save(); }
    public void setPriorityDistanceFactor(int factor) { priorityDistanceFactor = factor; validate(); save(); }

    public void setMaxTaskTimeMs(long ms) { maxTaskTimeMs = ms; validate(); save(); }
    public void setTaskYieldThresholdMs(long ms) { taskYieldThresholdMs = ms; validate(); save(); }
}