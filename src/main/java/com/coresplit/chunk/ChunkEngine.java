package com.coresplit.chunk;

import com.coresplit.CoreSplitMod;

import java.util.concurrent.CompletableFuture;

public class ChunkEngine {

    // 修复BUG: instance 未声明 volatile，虽然 synchronized 方法可保证可见性，但按项目约定
    // 线程共享变量应使用 volatile，并改用双重检查锁定以减少锁竞争
    private static volatile ChunkEngine instance;
    
    private final ChunkEngineConfig config;
    private final ChunkTaskScheduler taskScheduler;
    private final AsyncChunkIO asyncChunkIO;
    private final ParallelChunkGenerator parallelGenerator;
    private final EntityFilter entityFilter;
    
    private volatile boolean initialized = false;

    private ChunkEngine() {
        this.config = ChunkEngineConfig.getInstance();
        this.taskScheduler = new ChunkTaskScheduler(
                config.getGenerationThreads(),
                config.getIOThreads(),
                config.getMaxConcurrentGenerations(),
                config.getMaxConcurrentLoads(),
                config.getTaskPriorityBase(),
                config.getPriorityDistanceFactor());
        this.asyncChunkIO = new AsyncChunkIO(config.getIOThreads());
        this.parallelGenerator = new ParallelChunkGenerator(taskScheduler, config);
        this.entityFilter = new EntityFilter(
                config.getEntityPartitionSize(),
                config.getMaxEntitiesPerPartition());
        
        this.entityFilter.setEnabled(config.isEntityFilterEnabled());
        
        this.initialized = true;
        CoreSplitMod.LOGGER.info("[CoreSplit] ChunkEngine initialized successfully");
    }

    public static ChunkEngine getInstance() {
        // 修复BUG: 原方法整体 synchronized，每次调用都加锁，改用双重检查锁定提升性能
        ChunkEngine result = instance;
        if (result == null) {
            synchronized (ChunkEngine.class) {
                result = instance;
                if (result == null) {
                    result = new ChunkEngine();
                    instance = result;
                }
            }
        }
        return result;
    }

    public CompletableFuture<Void> generateChunk(int chunkX, int chunkZ, int playerX, int playerZ) {
        if (!initialized || !config.isParallelGenerationEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return parallelGenerator.generateChunkAsync(chunkX, chunkZ, playerX, playerZ, null);
    }

    public CompletableFuture<Void> generateChunkWithCallback(int chunkX, int chunkZ, int playerX, int playerZ,
                                                             ParallelChunkGenerator.GenerationCallback callback) {
        if (!initialized || !config.isParallelGenerationEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return parallelGenerator.generateChunkAsync(chunkX, chunkZ, playerX, playerZ, callback);
    }

    public CompletableFuture<java.nio.ByteBuffer> loadChunk(String worldDir, int chunkX, int chunkZ,
                                                             int playerX, int playerZ) {
        if (!initialized) {
            return CompletableFuture.failedFuture(new IllegalStateException("ChunkEngine not initialized"));
        }

        if (config.isAsyncIOEnabled()) {
            return asyncChunkIO.readChunkAsync(worldDir, chunkX, chunkZ);
        } else {
            // 修复BUG: 原 sync 路径提交空 Runnable 后 thenApply(v -> null) 返回 null ByteBuffer，
            // 调用方对结果解引用会 NPE，且实际并未执行任何加载逻辑，改返回失败 future 明确语义
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Sync chunk loading is not supported when async IO is disabled"));
        }
    }

    public CompletableFuture<Void> saveChunk(String worldDir, int chunkX, int chunkZ,
                                              java.nio.ByteBuffer data) {
        if (!initialized) {
            return CompletableFuture.failedFuture(new IllegalStateException("ChunkEngine not initialized"));
        }

        if (config.isAsyncIOEnabled()) {
            return asyncChunkIO.writeChunkAsync(worldDir, chunkX, chunkZ, data);
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    public void submitLoadingTask(int chunkX, int chunkZ, int playerX, int playerZ, Runnable task, boolean isNoTick) {
        if (!initialized || !config.isConcurrentLoadingEnabled()) {
            task.run();
            return;
        }

        // 修复BUG: effectiveMaxConcurrent 计算后从未使用（taskScheduler 已内置并发上限），属死代码，移除
        taskScheduler.submitLoadingTask(chunkX, chunkZ, playerX, playerZ, task, isNoTick);
    }

    public void submitGenerationTask(int chunkX, int chunkZ, int playerX, int playerZ, Runnable task, boolean isNoTick) {
        if (!initialized || !config.isParallelGenerationEnabled()) {
            task.run();
            return;
        }
        taskScheduler.submitGenerationTask(chunkX, chunkZ, playerX, playerZ, task, isNoTick);
    }

    public void addEntity(int entityId, double x, double y, double z, boolean isNoTickRelevant) {
        if (!initialized) return;
        entityFilter.addEntity(entityId, x, y, z, isNoTickRelevant);
    }

    public void updateEntity(int entityId, double x, double y, double z) {
        if (!initialized) return;
        entityFilter.updateEntity(entityId, x, y, z);
    }

    public void removeEntity(int entityId) {
        if (!initialized) return;
        entityFilter.removeEntity(entityId);
    }

    public boolean shouldProcessEntity(int entityId, int chunkX, int chunkZ) {
        if (!initialized) return true;
        return entityFilter.shouldProcessEntity(entityId, chunkX, chunkZ);
    }

    public void updateTaskPriority(int chunkX, int chunkZ, int newPlayerX, int newPlayerZ) {
        if (!initialized) return;
        taskScheduler.updateTaskPriority(chunkX, chunkZ, newPlayerX, newPlayerZ);
    }

    public void cancelTask(int chunkX, int chunkZ) {
        if (!initialized) return;
        taskScheduler.cancelTask(chunkX, chunkZ);
    }

    public void setNoTickMode(boolean enabled) {
        if (!initialized) return;
        entityFilter.setNoTickMode(enabled);
    }

    public void reloadConfig() {
        config.load();

        // 修复BUG: taskScheduler/asyncChunkIO/parallelGenerator 在构造时即固定线程数，
        // reload 后线程数等配置不会生效，需提示用户重启才能应用，否则产生配置与实际不符的逻辑错误
        CoreSplitMod.LOGGER.warn("[CoreSplit] ChunkEngine config reloaded; thread pool sizing changes require a full restart to take effect");

        entityFilter.setEnabled(config.isEntityFilterEnabled());
    }

    public void shutdown() {
        if (!initialized) return;
        
        CoreSplitMod.LOGGER.info("[CoreSplit] Shutting down ChunkEngine...");
        
        parallelGenerator.shutdown();
        taskScheduler.shutdown();
        asyncChunkIO.shutdown();
        
        entityFilter.clearAll();
        
        initialized = false;
        CoreSplitMod.LOGGER.info("[CoreSplit] ChunkEngine shutdown complete");
    }

    public boolean isInitialized() {
        return initialized;
    }

    public ChunkEngineConfig getConfig() {
        return config;
    }

    public ChunkTaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    public AsyncChunkIO getAsyncChunkIO() {
        return asyncChunkIO;
    }

    public ParallelChunkGenerator getParallelGenerator() {
        return parallelGenerator;
    }

    public EntityFilter getEntityFilter() {
        return entityFilter;
    }

    public EngineStats getStats() {
        return new EngineStats(
                taskScheduler.getActiveGenerationTasks(),
                taskScheduler.getActiveLoadingTasks(),
                taskScheduler.getPendingGenerationTasks(),
                taskScheduler.getPendingLoadingTasks(),
                asyncChunkIO.getActiveOperations(),
                asyncChunkIO.getPendingReadCount(),
                asyncChunkIO.getPendingWriteCount(),
                parallelGenerator.getPendingGenerationCount(),
                entityFilter.getTotalEntityCount(),
                entityFilter.getFilteredEntityCount(),
                entityFilter.getPartitionCount()
        );
    }

    public static class EngineStats {
        public final int activeGenerationTasks;
        public final int activeLoadingTasks;
        public final int pendingGenerationTasks;
        public final int pendingLoadingTasks;
        public final int activeIOOperations;
        public final int pendingReads;
        public final int pendingWrites;
        public final int pendingGenerations;
        public final int totalEntities;
        public final int filteredEntities;
        public final int entityPartitions;

        public EngineStats(int activeGenerationTasks, int activeLoadingTasks,
                           int pendingGenerationTasks, int pendingLoadingTasks,
                           int activeIOOperations, int pendingReads, int pendingWrites,
                           int pendingGenerations, int totalEntities, int filteredEntities,
                           int entityPartitions) {
            this.activeGenerationTasks = activeGenerationTasks;
            this.activeLoadingTasks = activeLoadingTasks;
            this.pendingGenerationTasks = pendingGenerationTasks;
            this.pendingLoadingTasks = pendingLoadingTasks;
            this.activeIOOperations = activeIOOperations;
            this.pendingReads = pendingReads;
            this.pendingWrites = pendingWrites;
            this.pendingGenerations = pendingGenerations;
            this.totalEntities = totalEntities;
            this.filteredEntities = filteredEntities;
            this.entityPartitions = entityPartitions;
        }
    }
}