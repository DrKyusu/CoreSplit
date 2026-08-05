package com.coresplit.scheduler;

import com.coresplit.CoreSplitMod;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskScheduler {

    private static final int MIN_CORES = 1;
    private static final int MIN_THREADS = 1;
    private static final float DEFAULT_THREAD_MULTIPLIER = 2.0f;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final int MAX_QUEUE_CAPACITY = 512;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private final int maxAvailableCores;
    private volatile int configuredCores;
    private volatile int configuredThreads;
    private volatile float threadMultiplier = DEFAULT_THREAD_MULTIPLIER;

    private volatile ThreadPoolExecutor executorService;
    private volatile boolean enabled = true;

    private final Object lock = new Object();

    public TaskScheduler() {
        // Use the new hardware-aware detector rather than a blind Runtime.availableProcessors()
        // call, which can under-count in containers or with OS affinity masks.
        this.maxAvailableCores = HardwareDetector.getLogicalCores();

        // Default cores: ALL cores minus 1 reserved for main/render thread on clients.
        // We don't distinguish client vs. server here; the -1 is a no-op at <=4 cores.
        int recommended = HardwareDetector.recommendCpuBoundThreads();
        this.configuredCores = Math.max(MIN_CORES, recommended);
        this.configuredThreads = calculateThreadsFromMultiplier(configuredCores);

        startExecutor();

        CoreSplitMod.LOGGER.info("[CoreSplit] TaskScheduler initialized - Max cores: {}, Initial cores: {}, Threads: {}",
                maxAvailableCores, configuredCores, configuredThreads);
    }

    /**
     * Construct a TaskScheduler with caller-supplied defaults (used by tests that need a
     * deterministic size). Hardware-validated and clamped.
     */
    public TaskScheduler(int cores, float multiplier) {
        this.maxAvailableCores = HardwareDetector.getLogicalCores();
        this.threadMultiplier = validateMultiplier(multiplier);
        this.configuredCores = validateCoreCount(cores);
        this.configuredThreads = calculateThreadsFromMultiplier(configuredCores);
        startExecutor();
        CoreSplitMod.LOGGER.info("[CoreSplit] TaskScheduler initialized (custom) - Max cores: {}, Cores: {}, Threads: {}",
                maxAvailableCores, configuredCores, configuredThreads);
    }

    public void applyConfiguration(int cores, int threads) {
        synchronized (lock) {
            cores = validateCoreCount(cores);
            threads = validateThreadCount(threads, cores);

            if (cores == this.configuredCores && threads == this.configuredThreads
                    && executorService != null && !executorService.isShutdown()
                    && executorService.getCorePoolSize() == threads) {
                return;
            }

            shutdownExecutor();

            this.configuredCores = cores;
            this.configuredThreads = threads;

            CoreSplitMod.LOGGER.info("[CoreSplit] TaskScheduler configuration updated - Cores: {}, Threads: {}",
                    configuredCores, configuredThreads);

            if (enabled) {
                startExecutor();
            }
        }
    }

    public void applyConfigurationWithMultiplier(int cores, float multiplier) {
        synchronized (lock) {
            multiplier = validateMultiplier(multiplier);
            this.threadMultiplier = multiplier;
            int threads = calculateThreadsFromMultiplier(cores);
            applyConfiguration(cores, threads);
        }
    }

    private int calculateThreadsFromMultiplier(int cores) {
        return Math.max(MIN_THREADS, (int) (cores * threadMultiplier));
    }

    private int validateCoreCount(int cores) {
        return Math.max(MIN_CORES, Math.min(maxAvailableCores, cores));
    }

    private int validateThreadCount(int threads, int cores) {
        int minThreads = cores;
        int maxThreads = cores * 4;
        return Math.max(minThreads, Math.min(maxThreads, threads));
    }

    private float validateMultiplier(float multiplier) {
        return Math.max(0.5f, Math.min(4.0f, multiplier));
    }

    private void startExecutor() {
        if (executorService != null && !executorService.isShutdown()) {
            return;
        }

        // Use a bounded queue + CallerRunsPolicy per project conventions to prevent OOM under
        // sustained load. Fixed-size pool with bounded work queue.
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                configuredThreads,
                configuredThreads,
                KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY),
                new CoreSplitThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        // Allow idle cores to time out so many-core systems don't hold 64+ idle threads forever.
        tpe.allowCoreThreadTimeOut(true);
        // Prestart all threads — first-submit latency matters for chunk generation, AI, etc.
        tpe.prestartAllCoreThreads();

        executorService = tpe;
        CoreSplitMod.LOGGER.info("[CoreSplit] TaskScheduler executor started with {} threads (queueCap={})",
                configuredThreads, MAX_QUEUE_CAPACITY);
    }

    private void shutdownExecutor() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            CoreSplitMod.LOGGER.info("[CoreSplit] TaskScheduler executor shutdown");
            executorService = null;
        }
    }

    public void shutdown() {
        synchronized (lock) {
            shutdownExecutor();
            enabled = false;
        }
    }

    public void submitTask(Runnable task) {
        if (!enabled || executorService == null) {
            task.run();
            return;
        }

        try {
            executorService.submit(task);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to submit task to scheduler: {}", e.getMessage());
            task.run();
        }
    }

    public void setEnabled(boolean enabled) {
        synchronized (lock) {
            boolean wasEnabled = this.enabled;
            this.enabled = enabled;
            if (enabled && !wasEnabled) {
                startExecutor();
            } else if (!enabled && wasEnabled) {
                shutdownExecutor();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getConfiguredCores() {
        return configuredCores;
    }

    public int getConfiguredThreads() {
        return configuredThreads;
    }

    public int getMaxAvailableCores() {
        return maxAvailableCores;
    }

    public float getThreadMultiplier() {
        return threadMultiplier;
    }

    public int getActiveThreadCount() {
        if (executorService == null) return 0;
        return executorService.getActiveCount();
    }

    public int getPoolSize() {
        if (executorService == null) return 0;
        return executorService.getPoolSize();
    }

    public int getQueueSize() {
        if (executorService == null) return 0;
        return executorService.getQueue().size();
    }

    public long getTaskCount() {
        if (executorService == null) return 0;
        return executorService.getTaskCount();
    }

    public long getCompletedTaskCount() {
        if (executorService == null) return 0;
        return executorService.getCompletedTaskCount();
    }

    /**
     * Thread priority tiers used by the pool factory. Critical tasks (chunk generation,
     * explosions) run at slightly higher priority; background cleanup at lower.
     */
    public enum ThreadPriorityTier {
        CRITICAL(Thread.MAX_PRIORITY - 1),
        HIGH(Thread.NORM_PRIORITY + 1),
        NORMAL(Thread.NORM_PRIORITY),
        LOW(Thread.MIN_PRIORITY + 2),
        BACKGROUND(Thread.MIN_PRIORITY);

        final int priority;
        ThreadPriorityTier(int p) { this.priority = p; }
    }

    private static class CoreSplitThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        private final int threadPriority;

        CoreSplitThreadFactory() {
            this(ThreadPriorityTier.HIGH);
        }

        CoreSplitThreadFactory(ThreadPriorityTier tier) {
            this.threadPriority = tier.priority;
            namePrefix = "coresplit-scheduler-" + poolNumber.getAndIncrement() + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(false);
            t.setPriority(threadPriority);
            // Opportunistic hybrid affinity pinning (silent no-op if unsupported).
            // Pin to the high-8 bitmask = P-cores on 8P+8E Intel.
            long hybridMask = HardwareDetector.isHybridSuspected() ? 0x00FFL : 0L;
            if (hybridMask != 0L) {
                HardwareDetector.tryPinThreadToPerformanceCores(hybridMask);
            }
            return t;
        }
    }
}