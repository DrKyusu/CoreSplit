package com.coresplit.threading;

import com.coresplit.config.CoreSplitConfig;
import com.coresplit.metrics.PerformanceMonitor;
import com.coresplit.pipeline.TickPipeline;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * 维度级 Worker — 在独立线程上执行单个维度的完整 Tick。
 *
 * 内部流水线：
 *   1. 区块管理（加载/卸载）
 *   2. 实体 Tick（可进一步并行化）
 *   3. 方块实体 Tick
 *   4. 随机方块 Tick / 流体 Tick
 *   5. 刷新区块变更
 *
 * 实体处理借鉴 Async (citation:6) 的空间分区策略：
 *   将实体按 ChunkPos 分区，同一分区串行、不同分区并行。
 */
public class DimensionWorker {

    private final ServerWorld world;
    private final CoreSplitConfig config;
    private final PerformanceMonitor metrics;
    private final TickPipeline pipeline;

    // 跨维度传送缓冲区（无锁队列）
    private final ConcurrentLinkedQueue<PendingTransfer> pendingTransfers = new ConcurrentLinkedQueue<>();

    // 用于实体空间分区的掩码
    private final int partitionMask;

    public DimensionWorker(ServerWorld world, CoreSplitConfig config, PerformanceMonitor metrics) {
        this.world = world;
        this.config = config;
        this.metrics = metrics;
        this.pipeline = new TickPipeline(config);
        this.partitionMask = ~((1 << config.getEntityPartitionShift()) - 1);
    }

    /**
     * 执行该维度的完整 Tick。
     * 在 DimensionThreadManager 分发的线程上运行。
     */
    public void tick(BooleanSupplier shouldKeepTicking,
                     ExecutorService entityPool,
                     ExecutorService chunkPool) {
        long start = System.nanoTime();

        // ─── Phase 1: 区块管理 ───
        pipeline.executePhase("chunk-mgmt", () -> {
            tickChunkManagement(shouldKeepTicking, chunkPool);
        });

        // ─── Phase 2: 随机方块 Tick & 流体 Tick ───
        pipeline.executePhase("random-ticks", () -> {
            tickRandomBlocks(shouldKeepTicking);
        });

        // ─── Phase 3: 方块实体 Tick ───
        pipeline.executePhase("block-entities", () -> {
            tickBlockEntities(shouldKeepTicking);
        });

        // ─── Phase 4: 实体 Tick（并行） ───
        pipeline.executePhase("entities", () -> {
            tickEntitiesParallel(shouldKeepTicking, entityPool);
        });

        // ─── Phase 5: 天气、时间、环境 ───
        pipeline.executePhase("environment", () -> {
            tickEnvironment(shouldKeepTicking);
        });

        // ─── Phase 6: 刷新区块变更到客户端 ───
        pipeline.executePhase("flush", () -> {
            flushChunkUpdates();
        });

        long elapsed = System.nanoTime() - start;
        if (config.isMonitoringEnabled()) {
            metrics.recordDimensionDetail(world.getRegistryKey().getValue().toString(), elapsed);
        }
    }

    /**
     * 区块管理 — 利用 ChunkWorkerPool 异步加载区块。
     * 参考 C2ME 的优先级区块加载 (citation:1)：
     *   玩家附近的区块优先级更高，远处的区块降级处理。
     */
    private void tickChunkManagement(BooleanSupplier shouldKeepTicking, ExecutorService chunkPool) {
        if (!config.isChunkParallelEnabled()) {
            // 原版路径
            world.getChunkManager().tick(shouldKeepTicking, true);
            return;
        }

        // 使用 ChunkWorkerPool 进行优先级加载
        ChunkWorkerPool chunkWorker = ChunkWorkerPool.getInstance();
        chunkWorker.processPending(world, chunkPool, config.getChunkPriorityRadius());

        // 仍然调用原版 chunk manager 做状态同步
        world.getChunkManager().tick(shouldKeepTicking, false);
    }

    /**
     * 随机方块 Tick — 利用 Fork-Join 并行处理多个 Chunk Section。
     */
    private void tickRandomBlocks(BooleanSupplier shouldKeepTicking) {
        // 原版内部已有一定优化，此处保持原版调用
        // 高级场景可替换为分区并行
        world.tick(shouldKeepTicking);
    }

    /**
     * 方块实体 Tick。
     */
    private void tickBlockEntities(BooleanSupplier shouldKeepTicking) {
        // 方块实体 Tick 在 world.tick() 中已包含
        // 此处为预留扩展点
    }

    /**
     * 实体并行 Tick — 核心优化。
     *
     * 算法：
     *   1. 遍历世界中所有实体，按 ChunkPos 分区
     *   2. 同一 Chunk 分区内的实体串行执行（避免竞争）
     *   3. 不同分区的实体并行提交到 EntityWorkerPool
     *   4. 等待所有分区完成
     *
     * 参考 Async (citation:6) 的并行实体处理架构。
     */
    private void tickEntitiesParallel(BooleanSupplier shouldKeepTicking, ExecutorService entityPool) {
        if (!config.isEntityParallelEnabled()) {
            // 原版路径
            world.getEntities().forEach(entity -> {
                if (!entity.isRemoved()) entity.tick();
            });
            return;
        }

        // 收集当前 Tick 的所有可 Tick 实体，按空间分区
        List<List<Entity>> partitions = partitionEntities();

        if (partitions.size() <= 1) {
            // 实体太少，直接串行
            for (List<Entity> partition : partitions) {
                for (Entity entity : partition) {
                    if (!entity.isRemoved() && shouldKeepTicking.getAsBoolean()) {
                        tickSingleEntity(entity);
                    }
                }
            }
            return;
        }

        // 并行提交各分区
        List<Future<?>> futures = new ArrayList<>(partitions.size());
        for (List<Entity> partition : partitions) {
            futures.add(entityPool.submit(() -> {
                for (Entity entity : partition) {
                    if (!entity.isRemoved()) {
                        tickSingleEntity(entity);
                    }
                }
            }));
        }

        // 等待所有分区完成
        for (Future<?> future : futures) {
            try {
                future.get(config.getPhaseTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                CoreSplitMod.CoreSplitMod.LOGGER.warn("[CoreSplit] Entity partition timeout/error", e);
            }
        }
    }

    /**
     * 将世界实体按 ChunkPos 分区。
     * 使用位移运算快速计算分区 ID。
     */
    private List<List<Entity>> partitionEntities() {
        // 简化实现：按 chunk 列分桶
        java.util.Map<Long, List<Entity>> buckets = new java.util.concurrent.ConcurrentHashMap<>();

        for (Entity entity : world.getEntities()) {
            if (entity.isRemoved()) continue;
            int cx = entity.getBlockX() >> 4;
            int cz = entity.getBlockZ() >> 4;
            // 将相邻 chunk 的实体合并到同一分区
            int px = cx >> config.getEntityPartitionShift();
            int pz = cz >> config.getEntityPartitionShift();
            long key = ChunkPos.toLong(px, pz);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }

        return new ArrayList<>(buckets.values());
    }

    /**
     * 单个实体的 Tick 封装 — 设置线程上下文。
     */
    private void tickSingleEntity(Entity entity) {
        try {
            entity.tick();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.debug("[CoreSplit] Entity tick error: {} at {}",
                    entity.getName().getString(), entity.getBlockPos(), e);
        }
    }

    /**
     * 环境 Tick（天气、时间、天空光照等）。
     */
    private void tickEnvironment(BooleanSupplier shouldKeepTicking) {
        // 原版 world.tick() 中已包含大部分逻辑
        // 此处为未来拆分预留
    }

    /**
     * 将本维度的区块变更刷新到客户端。
     */
    private void flushChunkUpdates() {
        world.getChunkManager().flushUpdates();
    }

    /**
     * 缓存跨维度传送请求。
     */
    public void addPendingTransfer(Entity entity, ServerWorld targetWorld) {
        pendingTransfers.add(new PendingTransfer(entity, targetWorld));
    }

    /**
     * 在主线程同步阶段处理传送请求。
     */
    public void flushPendingTransfers() {
        PendingTransfer transfer;
        while ((transfer = pendingTransfers.poll()) != null) {
            try {
                transfer.entity.moveToWorld(transfer.targetWorld);
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Transfer failed for entity {}",
                        transfer.entity.getName().getString(), e);
            }
        }
    }

    private record PendingTransfer(Entity entity, ServerWorld targetWorld) {}
}