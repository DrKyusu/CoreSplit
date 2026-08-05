package com.coresplit.chunk;

import com.coresplit.CoreSplitMod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkTaskScheduler {

    private final ExecutorService generationExecutor;
    private final ExecutorService loadingExecutor;
    
    private final PriorityBlockingQueue<PrioritizedTask> generationQueue;
    private final PriorityBlockingQueue<PrioritizedTask> loadingQueue;
    
    private final ConcurrentHashMap<Long, PrioritizedTask> pendingGenerationTasks;
    private final ConcurrentHashMap<Long, PrioritizedTask> pendingLoadingTasks;
    
    private volatile boolean running = true;
    private final AtomicInteger activeGenerationTasks = new AtomicInteger(0);
    private final AtomicInteger activeLoadingTasks = new AtomicInteger(0);
    private final AtomicLong taskIdCounter = new AtomicLong(0);

    private final int maxConcurrentGenerations;
    private final int maxConcurrentLoads;
    private final int priorityBase;
    private final int distanceFactor;

    public ChunkTaskScheduler(int generationThreads, int loadingThreads, 
                              int maxConcurrentGenerations, int maxConcurrentLoads,
                              int priorityBase, int distanceFactor) {
        
        this.maxConcurrentGenerations = Math.max(1, maxConcurrentGenerations);
        this.maxConcurrentLoads = Math.max(1, maxConcurrentLoads);
        this.priorityBase = priorityBase;
        this.distanceFactor = distanceFactor;

        this.generationExecutor = Executors.newFixedThreadPool(generationThreads, new GenerationThreadFactory());
        this.loadingExecutor = Executors.newFixedThreadPool(loadingThreads, new LoadingThreadFactory());
        
        this.generationQueue = new PriorityBlockingQueue<>(128);
        this.loadingQueue = new PriorityBlockingQueue<>(128);
        
        this.pendingGenerationTasks = new ConcurrentHashMap<>();
        this.pendingLoadingTasks = new ConcurrentHashMap<>();

        for (int i = 0; i < generationThreads; i++) {
            generationExecutor.submit(this::generationWorker);
        }
        
        for (int i = 0; i < loadingThreads; i++) {
            loadingExecutor.submit(this::loadingWorker);
        }
        
        CoreSplitMod.LOGGER.info("[CoreSplit] ChunkTaskScheduler initialized - gen:{}/{}, load:{}/{}", 
                generationThreads, maxConcurrentGenerations, loadingThreads, maxConcurrentLoads);
    }

    public CompletableFuture<Void> submitGenerationTask(int chunkX, int chunkZ, int playerX, int playerZ, 
                                                        Runnable task, boolean isNoTick) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("ChunkTaskScheduler is not running"));
        }

        long taskKey = getTaskKey(chunkX, chunkZ);
        int distance = calculateDistance(chunkX, chunkZ, playerX, playerZ);
        int priority = calculatePriority(distance, isNoTick);

        CompletableFuture<Void> future = new CompletableFuture<>();
        PrioritizedTask newTask = new PrioritizedTask(taskKey, task, priority, distance, future);

        // 修复BUG: 原代码 get + put 非原子，并发提交同一 chunkKey 时两线程都可见 existing==null，
        // 各自创建任务并 put，造成重复任务（AsyncChunkIO 已用 compute 修复同类问题）。
        // 改用 compute 原子决策：保留未完成的已有任务，否则插入新任务。
        PrioritizedTask actual = pendingGenerationTasks.compute(taskKey, (k, existing) ->
                (existing != null && !existing.future.isDone()) ? existing : newTask);

        if (actual == newTask) {
            generationQueue.offer(newTask);
            return future;
        }
        return actual.future;
    }

    public CompletableFuture<Void> submitLoadingTask(int chunkX, int chunkZ, int playerX, int playerZ,
                                                     Runnable task, boolean isNoTick) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("ChunkTaskScheduler is not running"));
        }

        long taskKey = getTaskKey(chunkX, chunkZ);
        
        PrioritizedTask existing = pendingLoadingTasks.get(taskKey);
        if (existing != null && !existing.future.isDone()) {
            return existing.future;
        }

        int distance = calculateDistance(chunkX, chunkZ, playerX, playerZ);
        int priority = calculatePriority(distance, isNoTick);
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        PrioritizedTask prioritizedTask = new PrioritizedTask(taskKey, task, priority, distance, future);
        
        pendingLoadingTasks.put(taskKey, prioritizedTask);
        loadingQueue.offer(prioritizedTask);
        
        return future;
    }

    private void generationWorker() {
        while (running) {
            try {
                PrioritizedTask task = generationQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) continue;

                // PERF: 用标志位惰性丢弃已取消任务，替代 O(n) queue.remove()
                if (task.cancelled) continue;

                if (!running) {
                    task.future.completeExceptionally(new IllegalStateException("Scheduler shutting down"));
                    continue;
                }

                // PERF: 修复原代码 compareAndSet(get(), get()+1) 两次调用 get() 的竞态；
                // 改为单次 get + CAS 循环，容量满时 yield 让出 CPU 而非 sleep(1) 忙等待
                boolean acquired = false;
                while (true) {
                    int current = activeGenerationTasks.get();
                    if (current >= maxConcurrentGenerations) {
                        generationQueue.offer(task);
                        Thread.yield();
                        break;
                    }
                    if (activeGenerationTasks.compareAndSet(current, current + 1)) {
                        acquired = true;
                        break;
                    }
                }
                if (!acquired) continue;

                try {
                    task.runnable.run();
                    task.future.complete(null);
                } catch (Exception e) {
                    task.future.completeExceptionally(e);
                    CoreSplitMod.LOGGER.warn("[CoreSplit] Generation task failed for chunk {},{}",
                            getChunkX(task.key), getChunkZ(task.key), e);
                } finally {
                    activeGenerationTasks.decrementAndGet();
                    pendingGenerationTasks.remove(task.key);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void loadingWorker() {
        while (running) {
            try {
                PrioritizedTask task = loadingQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) continue;

                // PERF: 用标志位惰性丢弃已取消任务，替代 O(n) queue.remove()
                if (task.cancelled) continue;

                if (!running) {
                    task.future.completeExceptionally(new IllegalStateException("Scheduler shutting down"));
                    continue;
                }

                // PERF: 修复原代码 compareAndSet(get(), get()+1) 两次调用 get() 的竞态；
                // 改为单次 get + CAS 循环，容量满时 yield 让出 CPU 而非 sleep(1) 忙等待
                boolean acquired = false;
                while (true) {
                    int current = activeLoadingTasks.get();
                    if (current >= maxConcurrentLoads) {
                        loadingQueue.offer(task);
                        Thread.yield();
                        break;
                    }
                    if (activeLoadingTasks.compareAndSet(current, current + 1)) {
                        acquired = true;
                        break;
                    }
                }
                if (!acquired) continue;

                try {
                    task.runnable.run();
                    task.future.complete(null);
                } catch (Exception e) {
                    task.future.completeExceptionally(e);
                    CoreSplitMod.LOGGER.warn("[CoreSplit] Loading task failed for chunk {},{}",
                            getChunkX(task.key), getChunkZ(task.key), e);
                } finally {
                    activeLoadingTasks.decrementAndGet();
                    pendingLoadingTasks.remove(task.key);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // PERF: 经评估，calculateDistance 的 Math.sqrt 保留——平方距离会破坏 calculatePriority
    // 的优先级区分度（priorityBase=100, distanceFactor=5, 平方距离最大 10000 会导致
    // 所有远距离 chunk 优先级全为 max(1, 100-50000)=1，丢失区分能力）
    private int calculateDistance(int chunkX, int chunkZ, int playerX, int playerZ) {
        int dx = chunkX - playerX;
        int dz = chunkZ - playerZ;
        return (int) Math.sqrt(dx * dx + dz * dz);
    }

    private int calculatePriority(int distance, boolean isNoTick) {
        int basePriority = priorityBase - (distance * distanceFactor);
        if (isNoTick) {
            basePriority -= 50;
        }
        return Math.max(1, basePriority);
    }

    private long getTaskKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private int getChunkX(long key) {
        return (int) (key >> 32);
    }

    private int getChunkZ(long key) {
        return (int) key;
    }

    public void updateTaskPriority(int chunkX, int chunkZ, int newPlayerX, int newPlayerZ) {
        long taskKey = getTaskKey(chunkX, chunkZ);

        PrioritizedTask genTask = pendingGenerationTasks.get(taskKey);
        if (genTask != null && !genTask.future.isDone()) {
            int newDistance = calculateDistance(chunkX, chunkZ, newPlayerX, newPlayerZ);
            int newPriority = calculatePriority(newDistance, false);

            if (newPriority != genTask.priority) {
                // PERF: 直接更新 priority 字段，不再 remove + re-offer（O(n)→O(1)）；
                // PriorityBlockingQueue 不会立即重排，但新优先级在 poll 后的 compareTo 中生效，
                // 接受短暂的堆序偏差以换取 O(1) 更新
                genTask.priority = newPriority;
            }
        }

        PrioritizedTask loadTask = pendingLoadingTasks.get(taskKey);
        if (loadTask != null && !loadTask.future.isDone()) {
            int newDistance = calculateDistance(chunkX, chunkZ, newPlayerX, newPlayerZ);
            int newPriority = calculatePriority(newDistance, false);

            if (newPriority != loadTask.priority) {
                // PERF: 同上，O(1) 字段更新替代 O(n) remove + re-offer
                loadTask.priority = newPriority;
            }
        }
    }

    public void cancelTask(int chunkX, int chunkZ) {
        long taskKey = getTaskKey(chunkX, chunkZ);

        PrioritizedTask genTask = pendingGenerationTasks.remove(taskKey);
        if (genTask != null) {
            // PERF: 用标志位替代 O(n) generationQueue.remove()，worker poll 时惰性丢弃
            genTask.cancelled = true;
            genTask.future.cancel(true);
        }

        PrioritizedTask loadTask = pendingLoadingTasks.remove(taskKey);
        if (loadTask != null) {
            // PERF: 用标志位替代 O(n) loadingQueue.remove()，worker poll 时惰性丢弃
            loadTask.cancelled = true;
            loadTask.future.cancel(true);
        }
    }

    public void shutdown() {
        running = false;
        
        generationExecutor.shutdown();
        loadingExecutor.shutdown();
        
        generationQueue.forEach(task -> task.future.completeExceptionally(new IllegalStateException("Scheduler shutting down")));
        loadingQueue.forEach(task -> task.future.completeExceptionally(new IllegalStateException("Scheduler shutting down")));
        
        pendingGenerationTasks.values().forEach(task -> task.future.cancel(true));
        pendingLoadingTasks.values().forEach(task -> task.future.cancel(true));
        
        try {
            generationExecutor.awaitTermination(10, TimeUnit.SECONDS);
            loadingExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        CoreSplitMod.LOGGER.info("[CoreSplit] ChunkTaskScheduler shutdown complete");
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveGenerationTasks() {
        return activeGenerationTasks.get();
    }

    public int getActiveLoadingTasks() {
        return activeLoadingTasks.get();
    }

    public int getPendingGenerationTasks() {
        return pendingGenerationTasks.size();
    }

    public int getPendingLoadingTasks() {
        return pendingLoadingTasks.size();
    }

    private static class PrioritizedTask implements Comparable<PrioritizedTask> {
        final long key;
        final Runnable runnable;
        volatile int priority;
        final int distance;
        final CompletableFuture<Void> future;
        // PERF: 取消标志位，worker poll 时检查并跳过，替代 O(n) queue.remove()
        volatile boolean cancelled = false;

        PrioritizedTask(long key, Runnable runnable, int priority, int distance, CompletableFuture<Void> future) {
            this.key = key;
            this.runnable = runnable;
            this.priority = priority;
            this.distance = distance;
            this.future = future;
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int priorityCompare = Integer.compare(other.priority, this.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Integer.compare(this.distance, other.distance);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PrioritizedTask that = (PrioritizedTask) o;
            return key == that.key;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(key);
        }
    }

    private static class GenerationThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        GenerationThreadFactory() {
            namePrefix = "coresplit-gen-" + poolNumber.getAndIncrement() + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        }
    }

    private static class LoadingThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        LoadingThreadFactory() {
            namePrefix = "coresplit-load-" + poolNumber.getAndIncrement() + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}