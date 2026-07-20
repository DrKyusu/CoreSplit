package com.coresplit.threading;

import com.coresplit.CoreSplitMod;
import com.coresplit.config.CoreSplitConfig;
import com.coresplit.metrics.PerformanceMonitor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 维度级线程管理器。
 *
 * 借鉴 DimensionalThreading (citation:12) 的核心思路：
 *   为每个注册的维度分配独立的 Daemon Thread，
 *   通过 BlockingQueue 接收主线程下发的 Tick 指令，
 *   使用 StampedLock 保障跨维度数据一致性。
 *
 * 增强点：
 *   - 自适应线程池大小（根据在线维度数动态调整）
 *   - Phase Barrier 同步（保证 Tick 阶段有序执行）
 *   - 超时保护（防止单维度卡死导致全局挂起）
 */
public class DimensionThreadManager {

    private final CoreSplitConfig config;
    private final PerformanceMonitor metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeDimensions = new AtomicInteger(0);

    private MinecraftServer server;
    private ExecutorService dimensionExecutor;
    private ExecutorService entityExecutor;
    private ExecutorService chunkExecutor;
    private PhaseBarrier dimensionBarrier;

    // 每个维度独立的 Worker 实例
    private final Map<ServerWorld, DimensionWorker> workers = new ConcurrentHashMap<>();

    public DimensionThreadManager(CoreSplitConfig config, PerformanceMonitor metrics) {
        this.config = config;
        this.metrics = metrics;
    }

    /**
     * 服务端启动时初始化所有线程池。
     */
    public void initialize(MinecraftServer server) {
        this.server = server;
        this.running.set(true);

        // 维度线程池 — 每个维度一个独立线程
        dimensionExecutor = new ThreadPoolExecutor(
                1, config.getDimensionThreadCount(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new CoreSplitThreadFactory("CoreSplit-Dim"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        ((ThreadPoolExecutor) dimensionExecutor).allowCoreThreadTimeOut(true);

        // 实体处理线程池 — 参考 Async 的多核实体处理 (citation:6)
        entityExecutor = Executors.newWorkStealingPool(config.getEntityWorkerCount());

        // 区块加载线程池 — 参考 C2ME 的优先级调度 (citation:1)
        chunkExecutor = new ThreadPoolExecutor(
                2, config.getChunkWorkerCount(),
                30L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(64),
                new CoreSplitThreadFactory("CoreSplit-Chunk"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // Phase Barrier — 最多同步 max(维度数, CPU核心数) 个线程
        int barrierParties = Math.max(config.getDimensionThreadCount(), 3) + 1; // +1 for main thread
        dimensionBarrier = new PhaseBarrier(barrierParties, config.getPhaseTimeoutMs());

        CoreSplitMod.LOGGER.info("[CoreSplit] Thread pools initialized.");
    }

    /**
     * 主线程调用此方法，将各维度的 Tick 分发到独立线程。
     * 替代原版 MinecraftServer 中顺序遍历维度的逻辑。
     */
    public void tickAllDimensions(BooleanSupplier shouldKeepTicking) {
        if (!running.get() || !config.isDimensionThreadingEnabled()) {
            // 回退到原版顺序处理
            tickVanilla(shouldKeepTicking);
            return;
        }

        long tickStart = System.nanoTime();

        // 确保所有维度都有对应的 Worker
        syncDimensionWorkers();

        // Phase 1: 分发各维度的 Tick 到独立线程
        int activeCount = 0;
        for (Map.Entry<ServerWorld, DimensionWorker> entry : workers.entrySet()) {
            ServerWorld world = entry.getKey();
            DimensionWorker worker = entry.getValue();

            if (world.getPlayers().isEmpty() && !world.getRegistryKey().getValue().getPath().equals("overworld")) {
                continue; // 跳过无玩家的非主世界维度
            }

            activeCount++;
            dimensionExecutor.execute(() -> {
                try {
                    worker.tick(shouldKeepTicking, entityExecutor, chunkExecutor);
                } catch (Exception e) {
                    CoreSplitMod.LOGGER.error("[CoreSplit] Dimension tick error: {}",
                            world.getRegistryKey().getValue(), e);
                } finally {
                    dimensionBarrier.arrive();
                }
            });
        }

        // 主线程也在 Barrier 中等待
        activeDimensions.set(activeCount);
        dimensionBarrier.await(activeCount + 1);

        // Phase 2: 跨维度同步（传送、数据包等）
        processCrossDimensionSync();

        long tickNanos = System.nanoTime() - tickStart;
        if (config.isMonitoringEnabled()) {
            metrics.recordDimensionTick(tickNanos, activeCount);
        }
    }

    /**
     * 原版回退路径：顺序遍历维度。
     */
    private void tickVanilla(BooleanSupplier shouldKeepTicking) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getPlayers().isEmpty()
                    && !world.getRegistryKey().getValue().getPath().equals("overworld")) {
                continue;
            }
            world.tick(shouldKeepTicking);
        }
    }

    /**
     * 同步维度 Worker 列表：新增的维度创建 Worker，移除的维度清理。
     */
    private void syncDimensionWorkers() {
        for (ServerWorld world : server.getWorlds()) {
            workers.computeIfAbsent(world, w -> {
                CoreSplitMod.LOGGER.info("[CoreSplit] Spawning worker for dimension: {}",
                        w.getRegistryKey().getValue());
                return new DimensionWorker(w, config, metrics);
            });
        }
        // 移除已不存在的维度
        workers.keySet().removeIf(world -> {
            boolean exists = server.getWorlds().contains(world);
            if (!exists) {
                CoreSplitMod.LOGGER.info("[CoreSplit] Retiring worker for removed dimension: {}",
                        world.getRegistryKey().getValue());
            }
            return !exists;
        });
    }

    /**
     * 跨维度同步：处理传送请求、跨维度数据包等。
     * 在所有维度线程完成 Tick 后、返回主线程前执行。
     */
    private void processCrossDimensionSync() {
        for (DimensionWorker worker : workers.values()) {
            worker.flushPendingTransfers();
        }
    }

    /**
     * 优雅关闭所有线程。
     */
    public void shutdown() {
        running.set(false);
        workers.clear();

        shutdownExecutor(dimensionExecutor, "Dimension");
        shutdownExecutor(entityExecutor, "Entity");
        shutdownExecutor(chunkExecutor, "Chunk");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                CoreSplitMod.LOGGER.warn("[CoreSplit] {} pool forced shutdown.", name);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ─── 查询接口 ───
    public boolean isEnabled() { return running.get() && config.isDimensionThreadingEnabled(); }
    public int getActiveDimensions() { return activeDimensions.get(); }
    public Map<ServerWorld, DimensionWorker> getWorkers() { return Map.copyOf(workers); }
    public ExecutorService getEntityExecutor() { return entityExecutor; }
    public ExecutorService getChunkExecutor() { return chunkExecutor; }

    /**
     * 自定义线程工厂，设置守护线程 + 异常处理。
     */
    private static class CoreSplitThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final String prefix;

        CoreSplitThreadFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    CoreSplitMod.LOGGER.error("[CoreSplit] Uncaught exception in {}", thread.getName(), ex));
            return t;
        }
    }
}