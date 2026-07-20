package com.coresplit;

import com.coresplit.command.CoreSplitCommand;
import com.coresplit.config.CoreSplitConfig;
import com.coresplit.metrics.PerformanceMonitor;
import com.coresplit.threading.DimensionThreadManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CoreSplit — 并行引擎入口。
 *
 * 架构概览：
 *   主线程（MinecraftServer.tick）仅负责调度与同步，
 *   将各维度的 Tick 任务分发至独立的 DimensionWorker 线程，
 *   每个 DimensionWorker 内部再通过 EntityWorkerPool / ChunkWorkerPool
 *   将实体计算与区块加载进一步并行化。
 *
 * 借鉴来源：
 *   - DimensionalThreading 的维度级线程隔离 (citation:12)
 *   - Async 的实体多线程处理 (citation:6)
 *   - C2ME 的优先级区块加载线程池 (citation:1)
 */
public class CoreSplitMod implements ModInitializer, DedicatedServerModInitializer {

    public static final String MOD_ID = "coresplit";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile CoreSplitConfig config;
    private static volatile DimensionThreadManager dimensionManager;
    private static volatile PerformanceMonitor perfMonitor;

    @Override
    public void onInitialize() {
        LOGGER.info("[CoreSplit] Initializing parallel tick engine...");
        bootstrap();
    }

    @Override
    public void onInitializeServer() {
        LOGGER.info("[CoreSplit] Server-side initialization complete.");
    }

    private void bootstrap() {
        config = CoreSplitConfig.load();
        perfMonitor = new PerformanceMonitor(config);
        dimensionManager = new DimensionThreadManager(config, perfMonitor);

        // 服务端生命周期钩子
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            dimensionManager.initialize(server);
            LOGGER.info("[CoreSplit] Dimension thread pool ready — {} worker(s), {} entity thread(s), {} chunk thread(s).",
                    config.getDimensionThreadCount(),
                    config.getEntityWorkerCount(),
                    config.getChunkWorkerCount());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            dimensionManager.shutdown();
            perfMonitor.dumpSummary();
            LOGGER.info("[CoreSplit] All worker threads terminated. Final metrics dumped.");
        });

        // 注册命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CoreSplitCommand.register(dispatcher, perfMonitor, dimensionManager);
        });

        LOGGER.info("[CoreSplit] Bootstrap complete. Engine is armed.");
    }

    public static CoreSplitConfig getConfig()       { return config; }
    public static DimensionThreadManager getManager() { return dimensionManager; }
    public static PerformanceMonitor getMetrics()     { return perfMonitor; }
}