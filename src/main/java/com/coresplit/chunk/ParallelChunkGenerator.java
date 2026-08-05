package com.coresplit.chunk;

import com.coresplit.CoreSplitMod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ParallelChunkGenerator {

    private static final int MAX_NEIGHBOR_WAIT_MS = 5000;
    private static final int CHUNK_SECTION_HEIGHT = 16;

    // PERF: tan(y/5000) for y in [0,255]，单调近线性，预计算 256 项 LUT 替代每像素 Math.tan 调用
    private static final float[] TAN_LUT = new float[256];
    static {
        for (int y = 0; y < 256; y++) {
            TAN_LUT[y] = (float) Math.tan((y / 100.0) / 50.0);
        }
    }
    
    private final ChunkTaskScheduler scheduler;
    private final ChunkEngineConfig config;
    private final ExecutorService workerExecutor;
    
    private final ConcurrentHashMap<Long, GenerationContext> generationContexts;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingGenerations;
    
    private volatile boolean running = true;

    public ParallelChunkGenerator(ChunkTaskScheduler scheduler, ChunkEngineConfig config) {
        this.scheduler = scheduler;
        this.config = config;
        // 修复BUG: config 已传入但未用于线程数，原代码直接用 availableProcessors()/2，
        // 导致配置中的 generationThreads 失效，改用 config.getGenerationThreads()
        int workerThreads = (config != null) ?
                Math.max(1, config.getGenerationThreads()) : 2;
        this.workerExecutor = Executors.newFixedThreadPool(workerThreads, new WorkerThreadFactory());

        this.generationContexts = new ConcurrentHashMap<>();
        this.pendingGenerations = new ConcurrentHashMap<>();

        CoreSplitMod.LOGGER.info("[CoreSplit] ParallelChunkGenerator initialized with {} worker threads", workerThreads);
    }

    public CompletableFuture<Void> generateChunkAsync(int chunkX, int chunkZ, int playerX, int playerZ,
                                                      GenerationCallback callback) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("ParallelChunkGenerator is not running"));
        }

        long chunkKey = getChunkKey(chunkX, chunkZ);

        // 修复BUG: pendingGenerations 的 get 与 put 非原子，并发调用同一 chunkKey 时两个线程
        // 可能都看到 existing 为 null/done，各自创建 future 并 put，造成重复生成与资源浪费，
        // 改用 ConcurrentHashMap.compute 原子地检查并设置
        final CompletableFuture<Void> newFuture = new CompletableFuture<>();
        CompletableFuture<Void> future = pendingGenerations.compute(chunkKey,
                (k, existing) -> (existing != null && !existing.isDone()) ? existing : newFuture);
        if (future != newFuture) {
            return future;
        }

        GenerationContext context = new GenerationContext(chunkX, chunkZ);
        generationContexts.put(chunkKey, context);

        submitGenerationTasks(chunkX, chunkZ, playerX, playerZ, context, callback, future);

        return future;
    }

    private void submitGenerationTasks(int chunkX, int chunkZ, int playerX, int playerZ,
                                       GenerationContext context, GenerationCallback callback,
                                       CompletableFuture<Void> completionFuture) {
        List<CompletableFuture<Void>> subtasks = new ArrayList<>();

        CompletableFuture<Void> noiseTask = scheduler.submitGenerationTask(
                chunkX, chunkZ, playerX, playerZ,
                () -> {
                    try {
                        context.noiseData = generateTerrainNoise(chunkX, chunkZ);
                        context.stageCompleted(GenerationStage.NOISE);
                    } catch (Exception e) {
                        context.setError(e);
                    }
                }, false);
        subtasks.add(noiseTask);

        CompletableFuture<Void> biomeTask = scheduler.submitGenerationTask(
                chunkX, chunkZ, playerX, playerZ,
                () -> {
                    try {
                        context.biomeData = assignBiomes(chunkX, chunkZ);
                        context.stageCompleted(GenerationStage.BIOME);
                    } catch (Exception e) {
                        context.setError(e);
                    }
                }, false);
        subtasks.add(biomeTask);

        CompletableFuture<Void> structureTask = scheduler.submitGenerationTask(
                chunkX, chunkZ, playerX, playerZ,
                () -> {
                    try {
                        context.structureData = generateStructures(chunkX, chunkZ);
                        context.stageCompleted(GenerationStage.STRUCTURE);
                    } catch (Exception e) {
                        context.setError(e);
                    }
                }, false);
        subtasks.add(structureTask);

        CompletableFuture.allOf(subtasks.toArray(new CompletableFuture[0])).thenRunAsync(() -> {
            if (context.hasError()) {
                completionFuture.completeExceptionally(context.getError());
                cleanup(chunkX, chunkZ);
                return;
            }

            try {
                context.blockData = buildTerrain(chunkX, chunkZ, context);
                context.stageCompleted(GenerationStage.TERRAIN);

                if (callback != null) {
                    callback.onChunkGenerated(chunkX, chunkZ, context);
                }

                completionFuture.complete(null);
            } catch (Exception e) {
                completionFuture.completeExceptionally(e);
            } finally {
                cleanup(chunkX, chunkZ);
            }
        }, workerExecutor).exceptionally(ex -> {
            // 修复BUG: 当子任务被调度器拒绝(failedFuture)导致 allOf 异常完成时，thenRunAsync 不会执行；
            // 或 workerExecutor 关闭拒绝执行时，thenRunAsync 同样不执行。
            // 两种情况下 completionFuture 永不完成、cleanup 永不调用，造成 future 悬空与上下文泄漏。
            completionFuture.completeExceptionally(ex);
            cleanup(chunkX, chunkZ);
            return null;
        });
    }

    private float[] generateTerrainNoise(int chunkX, int chunkZ) {
        float[] noise = new float[16 * 16 * 256];

        // PERF: 每 chunk 预计算 sin(nx) 16 项、cos(nz) 16 项，避免 65536 次三角函数调用
        float[] sinX = new float[16];
        for (int x = 0; x < 16; x++) {
            sinX[x] = (float) Math.sin((chunkX * 16 + x) / 100.0);
        }
        float[] cosZ = new float[16];
        for (int z = 0; z < 16; z++) {
            cosZ[z] = (float) Math.cos((chunkZ * 16 + z) / 100.0);
        }

        for (int y = 0; y < 256; y++) {
            float tanY = TAN_LUT[y];  // PERF: LUT 替代 Math.tan
            for (int z = 0; z < 16; z++) {
                float cosZVal = cosZ[z];
                for (int x = 0; x < 16; x++) {
                    noise[y * 256 + z * 16 + x] = sinX[x] * cosZVal * tanY;
                }
            }
        }
        return noise;
    }

    private int[] assignBiomes(int chunkX, int chunkZ) {
        int[] biomes = new int[16 * 16];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                double nx = (chunkX * 16 + x) / 200.0;
                double nz = (chunkZ * 16 + z) / 200.0;
                biomes[z * 16 + x] = (int) ((Math.sin(nx) + Math.cos(nz)) * 10 + 10);
            }
        }
        return biomes;
    }

    private byte[] generateStructures(int chunkX, int chunkZ) {
        byte[] structures = new byte[16 * 16 * 256];
        int centerX = 8, centerZ = 8;
        for (int y = 60; y < 70; y++) {
            for (int z = centerZ - 3; z <= centerZ + 3; z++) {
                for (int x = centerX - 3; x <= centerX + 3; x++) {
                    if (x >= 0 && x < 16 && z >= 0 && z < 16) {
                        structures[y * 256 + z * 16 + x] = 1;
                    }
                }
            }
        }
        return structures;
    }

    private int[] buildTerrain(int chunkX, int chunkZ, GenerationContext context) {
        int[] blocks = new int[16 * 16 * 256];
        
        for (int y = 0; y < 256; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = y * 256 + z * 16 + x;
                    float noiseValue = context.noiseData != null ? context.noiseData[index] : 0;
                    int height = (int) (64 + noiseValue * 20);
                    
                    if (y == 0) {
                        blocks[index] = 7;
                    } else if (y < height - 4) {
                        blocks[index] = 1;
                    } else if (y < height) {
                        blocks[index] = 3;
                    } else if (y == height) {
                        blocks[index] = 2;
                    } else if (y <= 62) {
                        blocks[index] = 9;
                    } else {
                        blocks[index] = 0;
                    }
                }
            }
        }
        
        if (context.structureData != null) {
            for (int i = 0; i < context.structureData.length; i++) {
                if (context.structureData[i] != 0) {
                    blocks[i] = 42;
                }
            }
        }
        
        return blocks;
    }

    private boolean waitForNeighbors(int chunkX, int chunkZ, GenerationStage requiredStage) {
        long startTime = System.currentTimeMillis();
        int[][] neighborOffsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        for (int[] offset : neighborOffsets) {
            long neighborKey = getChunkKey(chunkX + offset[0], chunkZ + offset[1]);
            GenerationContext neighborContext = generationContexts.get(neighborKey);
            if (neighborContext == null) {
                return false;
            }

            // PERF: 用 wait/notifyAll 替代 Thread.sleep(10) 轮询，减少 CPU 空转
            synchronized (neighborContext) {
                while (!neighborContext.isStageCompleted(requiredStage)) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (elapsed >= MAX_NEIGHBOR_WAIT_MS) {
                        return false;
                    }
                    long remaining = MAX_NEIGHBOR_WAIT_MS - elapsed;
                    try {
                        neighborContext.wait(Math.min(remaining, 100));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private void cleanup(int chunkX, int chunkZ) {
        long chunkKey = getChunkKey(chunkX, chunkZ);
        generationContexts.remove(chunkKey);
        pendingGenerations.remove(chunkKey);
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public void shutdown() {
        running = false;
        
        workerExecutor.shutdown();
        
        pendingGenerations.values().forEach(f -> f.completeExceptionally(new IllegalStateException("ParallelChunkGenerator shutting down")));
        
        try {
            if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        generationContexts.clear();
        pendingGenerations.clear();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] ParallelChunkGenerator shutdown complete");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPendingGenerationCount() {
        return pendingGenerations.size();
    }

    public interface GenerationCallback {
        void onChunkGenerated(int chunkX, int chunkZ, GenerationContext context);
    }

    public static class GenerationContext {
        public final int chunkX;
        public final int chunkZ;
        
        public float[] noiseData;
        public int[] biomeData;
        public byte[] structureData;
        public int[] blockData;
        
        private final AtomicInteger completedStages = new AtomicInteger(0);
        private final AtomicReference<Exception> error = new AtomicReference<>(null);
        
        private static final int NOISE_BIT = 1 << 0;
        private static final int BIOME_BIT = 1 << 1;
        private static final int STRUCTURE_BIT = 1 << 2;
        private static final int TERRAIN_BIT = 1 << 3;

        public GenerationContext(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public void stageCompleted(GenerationStage stage) {
            int current;
            do {
                current = completedStages.get();
            } while (!completedStages.compareAndSet(current, current | stage.bitValue));
            // PERF: 通知等待此 stage 完成的线程，替代 waitForNeighbors 中的 sleep(10) 轮询
            synchronized (this) {
                notifyAll();
            }
        }

        public boolean isStageCompleted(GenerationStage stage) {
            return (completedStages.get() & stage.bitValue) != 0;
        }

        public void setError(Exception e) {
            error.compareAndSet(null, e);
        }

        public boolean hasError() {
            return error.get() != null;
        }

        public Exception getError() {
            return error.get();
        }

        public int getCompletedStageCount() {
            return Integer.bitCount(completedStages.get());
        }
    }

    public enum GenerationStage {
        NOISE(1 << 0),
        BIOME(1 << 1),
        STRUCTURE(1 << 2),
        TERRAIN(1 << 3),
        FEATURE(1 << 4),
        ENTITY(1 << 5);

        final int bitValue;

        GenerationStage(int bitValue) {
            this.bitValue = bitValue;
        }
    }

    private static class WorkerThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        WorkerThreadFactory() {
            namePrefix = "coresplit-gen-worker-" + poolNumber.getAndIncrement() + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        }
    }
}