package com.coresplit.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.coresplit.CoreSplitMod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 配置系统，采用 night-config 的 TOML 格式 (citation:1)。
 * 首次启动自动生成默认配置；支持运行时热更新。
 */
public class CoreSplitConfig {

    // ─── 维度线程 ───
    private boolean dimensionThreadingEnabled = true;
    private int dimensionThreadCount = 0; // 0 = auto (=维度数量)

    // ─── 实体处理 ───
    private boolean entityParallelEnabled = true;
    private int entityWorkerCount = 0;    // 0 = auto (CPU核心/2)
    private int entityPartitionShift = 4; // 2^4 = 16 chunk 粒度

    // ─── 区块加载 ───
    private boolean chunkParallelEnabled = true;
    private int chunkWorkerCount = 0;     // 0 = auto
    private int chunkPriorityRadius = 8;  // 玩家周围 8 区块高优先级
    private int maxChunksInMemory = 2048;

    // ─── 流水线 ───
    private boolean pipelineEnabled = true;
    private int phaseTimeoutMs = 5000;

    // ─── 监控 ───
    private boolean monitoringEnabled = true;
    private boolean logPerTickMetrics = false;

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("coresplit.toml");

    public static CoreSplitConfig load() {
        CoreSplitConfig cfg = new CoreSplitConfig();

        // 首次启动：从 resources 拷贝默认配置
        if (!Files.exists(CONFIG_PATH)) {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                try (InputStream in = CoreSplitConfig.class.getResourceAsStream("/coresplit-default.toml")) {
                    if (in != null) {
                        Files.copy(in, CONFIG_PATH);
                    } else {
                        // 手动写入默认值
                        writeDefault(cfg);
                    }
                }
            } catch (IOException e) {
                CoreSplitMod.LOGGER.error("[CoreSplit] Failed to create default config", e);
            }
        }

        // 读取配置
        try (FileConfig fileConfig = FileConfig.of(CONFIG_PATH)) {
            fileConfig.load();

            cfg.dimensionThreadingEnabled = fileConfig.getOrElse("dimension.enabled", true);
            cfg.dimensionThreadCount      = fileConfig.getOrElse("dimension.thread_count", 0);

            cfg.entityParallelEnabled     = fileConfig.getOrElse("entity.enabled", true);
            cfg.entityWorkerCount         = fileConfig.getOrElse("entity.worker_count", 0);
            cfg.entityPartitionShift      = fileConfig.getOrElse("entity.partition_shift", 4);

            cfg.chunkParallelEnabled      = fileConfig.getOrElse("chunk.enabled", true);
            cfg.chunkWorkerCount          = fileConfig.getOrElse("chunk.worker_count", 0);
            cfg.chunkPriorityRadius       = fileConfig.getOrElse("chunk.priority_radius", 8);
            cfg.maxChunksInMemory         = fileConfig.getOrElse("chunk.max_in_memory", 2048);

            cfg.pipelineEnabled           = fileConfig.getOrElse("pipeline.enabled", true);
            cfg.phaseTimeoutMs            = fileConfig.getOrElse("pipeline.phase_timeout_ms", 5000);

            cfg.monitoringEnabled         = fileConfig.getOrElse("monitoring.enabled", true);
            cfg.logPerTickMetrics         = fileConfig.getOrElse("monitoring.log_per_tick", false);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Config load failed, using defaults.", e);
        }

        // 自动检测硬件
        if (cfg.dimensionThreadCount <= 0) {
            cfg.dimensionThreadCount = Math.max(3, Runtime.getRuntime().availableProcessors() / 2);
        }
        if (cfg.entityWorkerCount <= 0) {
            cfg.entityWorkerCount = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        }
        if (cfg.chunkWorkerCount <= 0) {
            cfg.chunkWorkerCount = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        }

        CoreSplitMod.LOGGER.info("[CoreSplit] Config loaded: dimThreads={}, entityWorkers={}, chunkWorkers={}",
                cfg.dimensionThreadCount, cfg.entityWorkerCount, cfg.chunkWorkerCount);

        return cfg;
    }

    private static void writeDefault(CoreSplitConfig cfg) {
        try {
            Files.writeString(CONFIG_PATH, """
                    # CoreSplit Configuration
                    # Auto-generated on first launch.

                    [dimension]
                    enabled = true
                    thread_count = 0        # 0 = auto-detect

                    [entity]
                    enabled = true
                    worker_count = 0        # 0 = auto-detect
                    partition_shift = 4     # 2^4 = 16-chunk spatial partition

                    [chunk]
                    enabled = true
                    worker_count = 0
                    priority_radius = 8
                    max_in_memory = 2048

                    [pipeline]
                    enabled = true
                    phase_timeout_ms = 5000

                    [monitoring]
                    enabled = true
                    log_per_tick = false
                    """);
        } catch (IOException e) {
            CoreSplitMod.LOGGER.error("Failed to write default config", e);
        }
    }

    // ─── Getters ───
    public boolean isDimensionThreadingEnabled() { return dimensionThreadingEnabled; }
    public int getDimensionThreadCount()         { return dimensionThreadCount; }
    public boolean isEntityParallelEnabled()     { return entityParallelEnabled; }
    public int getEntityWorkerCount()            { return entityWorkerCount; }
    public int getEntityPartitionShift()         { return entityPartitionShift; }
    public boolean isChunkParallelEnabled()      { return chunkParallelEnabled; }
    public int getChunkWorkerCount()             { return chunkWorkerCount; }
    public int getChunkPriorityRadius()          { return chunkPriorityRadius; }
    public int getMaxChunksInMemory()            { return maxChunksInMemory; }
    public boolean isPipelineEnabled()           { return pipelineEnabled; }
    public int getPhaseTimeoutMs()               { return phaseTimeoutMs; }
    public boolean isMonitoringEnabled()         { return monitoringEnabled; }
    public boolean isLogPerTickMetrics()         { return logPerTickMetrics; }
}