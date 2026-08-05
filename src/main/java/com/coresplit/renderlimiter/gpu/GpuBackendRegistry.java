package com.coresplit.renderlimiter.gpu;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GpuBackendRegistry {

    private static final GpuBackendRegistry INSTANCE = new GpuBackendRegistry();

    private static final long HEALTH_CHECK_INTERVAL_MS = 10_000L;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long SLOW_TASK_THRESHOLD_NS = 100_000_000L; // 100ms
    private static final int MAX_SLOW_TASKS_BEFORE_DEGRADE = 5;

    private final Map<BackendType, GpuBackend> backends = new ConcurrentHashMap<>();
    private volatile GpuBackend activeBackend = null;
    private volatile boolean initialized = false;
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "GpuBackend-Async");
        t.setDaemon(true);
        return t;
    });

    // 健康监控相关
    private volatile long lastHealthCheckTime = 0;
    private volatile int consecutiveFailures = 0;
    private volatile int slowTaskCount = 0;
    private volatile boolean degradedMode = false;

    private GpuBackendRegistry() {
    }

    public static GpuBackendRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (initialized) return;

        registerBackend(new CpuFallbackBackend());
        detectAndRegisterBackends();
        selectBestBackend();

        if (activeBackend != null && !activeBackend.isInitialized()) {
            if (!activeBackend.initialize()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to initialize {} backend, falling back to CPU", activeBackend.getName());
                activeBackend = backends.get(BackendType.CPU_FALLBACK);
                activeBackend.initialize();
            }
        }

        initialized = true;
        CoreSplitMod.LOGGER.info("[CoreSplit] GPU Backend Registry initialized with {} backends, active: {}", backends.size(), activeBackend.getName());
    }

    private void detectAndRegisterBackends() {
        for (BackendType type : BackendType.values()) {
            if (type == BackendType.CPU_FALLBACK) continue;
            if (isBackendAvailable(type)) {
                tryRegisterBackend(type);
            }
        }
    }

    private boolean isBackendAvailable(BackendType type) {
        if (!type.requiresDetection()) return true;
        try {
            Class.forName(type.getDetectionClass());
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            CoreSplitMod.LOGGER.debug("[CoreSplit] {} backend not available: {}", type.getDisplayName(), e.getMessage());
            return false;
        }
    }

    private void tryRegisterBackend(BackendType type) {
        try {
            GpuBackend backend = createBackend(type);
            if (backend != null) {
                registerBackend(backend);
                CoreSplitMod.LOGGER.info("[CoreSplit] Registered {} backend", type.getDisplayName());
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to register {} backend: {}", type.getDisplayName(), e.getMessage());
        }
    }

    private GpuBackend createBackend(BackendType type) {
        return switch (type) {
            case OPENCL -> new OpenClBackend();
            case VULKAN -> new VulkanBackend();
            case OPENGL -> new OpenGlBackend();
            case CUDA -> new CudaBackend();
            default -> null;
        };
    }

    private void registerBackend(GpuBackend backend) {
        backends.put(backend.getType(), backend);
    }

    private void selectBestBackend() {
        List<GpuBackend> available = new ArrayList<>();
        for (GpuBackend backend : backends.values()) {
            if (backend.isAvailable()) {
                available.add(backend);
            }
        }

        if (available.isEmpty()) {
            activeBackend = backends.get(BackendType.CPU_FALLBACK);
            return;
        }

        available.sort(this::compareBackends);
        activeBackend = available.get(0);

        CoreSplitMod.LOGGER.info("[CoreSplit] Selected {} as active GPU backend", activeBackend.getName());
    }

    private int compareBackends(GpuBackend a, GpuBackend b) {
        int priorityA = getBackendPriority(a.getType());
        int priorityB = getBackendPriority(b.getType());
        return Integer.compare(priorityB, priorityA);
    }

    private int getBackendPriority(BackendType type) {
        return switch (type) {
            case CUDA -> 4;
            case OPENCL -> 3;
            case VULKAN -> 2;
            case OPENGL -> 1;
            case CPU_FALLBACK -> 0;
        };
    }

    public synchronized boolean switchBackend(BackendType type) {
        if (!initialized) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Cannot switch backend: registry not initialized");
            return false;
        }

        GpuBackend newBackend = backends.get(type);
        if (newBackend == null) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] {} backend not registered", type.getDisplayName());
            return false;
        }

        if (!newBackend.isAvailable()) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] {} backend not available", type.getDisplayName());
            return false;
        }

        if (activeBackend != null && activeBackend.getType() == type) {
            CoreSplitMod.LOGGER.debug("[CoreSplit] {} backend is already active", type.getDisplayName());
            return true;
        }

        if (activeBackend != null && activeBackend.isInitialized()) {
            CoreSplitMod.LOGGER.debug("[CoreSplit] Shutting down {} backend", activeBackend.getName());
            activeBackend.shutdown();
        }

        if (newBackend.initialize()) {
            activeBackend = newBackend;
            // 用户手动切换后端时，重置降级状态和计数器
            degradedMode = false;
            consecutiveFailures = 0;
            slowTaskCount = 0;
            CoreSplitMod.LOGGER.info("[CoreSplit] Switched to {} backend", type.getDisplayName());
            return true;
        } else {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to initialize {} backend, falling back to CPU", type.getDisplayName());
            activeBackend = backends.get(BackendType.CPU_FALLBACK);
            activeBackend.initialize();
            return false;
        }
    }

    public GpuBackend getActiveBackend() {
        return activeBackend;
    }

    public Collection<GpuBackend> getAllBackends() {
        return Collections.unmodifiableCollection(backends.values());
    }

    public GpuBackend getBackend(BackendType type) {
        return backends.get(type);
    }

    public boolean isBackendAvailable(BackendType type, boolean checkInitialized) {
        GpuBackend backend = backends.get(type);
        if (backend == null) return false;
        if (!checkInitialized) return backend.isAvailable();
        return backend.isAvailable() && backend.isInitialized();
    }

    public void shutdown() {
        for (GpuBackend backend : backends.values()) {
            if (backend.isInitialized()) {
                backend.shutdown();
            }
        }
        asyncExecutor.shutdown();
        initialized = false;
        CoreSplitMod.LOGGER.info("[CoreSplit] GPU Backend Registry shutdown");
    }

    public <T> T executeTask(GpuTask<T> task) {
        if (activeBackend == null) {
            return null;
        }

        long startTime = System.nanoTime();
        try {
            T result = activeBackend.execute(task);
            long duration = System.nanoTime() - startTime;

            if (duration > SLOW_TASK_THRESHOLD_NS && !degradedMode
                    && activeBackend.getType() != BackendType.CPU_FALLBACK) {
                slowTaskCount++;
                CoreSplitMod.LOGGER.debug("[CoreSplit] Slow GPU task detected: {}ms", duration / 1_000_000);
                if (slowTaskCount >= MAX_SLOW_TASKS_BEFORE_DEGRADE) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] GPU backend too slow ({} slow tasks), degrading to CPU fallback",
                            slowTaskCount);
                    degradeToCpu();
                }
            } else {
                slowTaskCount = Math.max(0, slowTaskCount - 1);
            }

            consecutiveFailures = 0;
            return result;
        } catch (Exception e) {
            consecutiveFailures++;
            CoreSplitMod.LOGGER.warn("[CoreSplit] GPU task failed (consecutive: {}): {}",
                    consecutiveFailures, e.getMessage());
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
                    && activeBackend.getType() != BackendType.CPU_FALLBACK) {
                CoreSplitMod.LOGGER.error("[CoreSplit] GPU backend failed {} times, degrading to CPU fallback",
                        MAX_CONSECUTIVE_FAILURES);
                degradeToCpu();
            }
            return null;
        } finally {
            maybeCheckHealth();
        }
    }

    public void executeTaskAsync(GpuTask<Void> task) {
        if (activeBackend == null) {
            return;
        }
        asyncExecutor.submit(() -> {
            long startTime = System.nanoTime();
            try {
                activeBackend.execute(task);
                long duration = System.nanoTime() - startTime;
                if (duration > SLOW_TASK_THRESHOLD_NS && !degradedMode
                        && activeBackend.getType() != BackendType.CPU_FALLBACK) {
                    slowTaskCount++;
                    if (slowTaskCount >= MAX_SLOW_TASKS_BEFORE_DEGRADE) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Async GPU tasks too slow, degrading to CPU fallback");
                        degradeToCpu();
                    }
                }
            } catch (Exception e) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
                        && activeBackend.getType() != BackendType.CPU_FALLBACK) {
                    CoreSplitMod.LOGGER.error("[CoreSplit] Async GPU backend failing, degrading to CPU fallback");
                    degradeToCpu();
                }
            }
        });
    }

    /**
     * 降级到 CPU fallback，避免 GPU 问题导致持续掉帧。
     */
    private synchronized void degradeToCpu() {
        if (degradedMode) return;
        degradedMode = true;

        GpuBackend cpuBackend = backends.get(BackendType.CPU_FALLBACK);
        if (cpuBackend == null) {
            cpuBackend = new CpuFallbackBackend();
            backends.put(BackendType.CPU_FALLBACK, cpuBackend);
        }

        if (activeBackend != null && activeBackend.isInitialized()) {
            try {
                activeBackend.shutdown();
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Error shutting down faulty GPU backend", e);
            }
        }

        cpuBackend.initialize();
        activeBackend = cpuBackend;
        slowTaskCount = 0;
        consecutiveFailures = 0;

        CoreSplitMod.LOGGER.info("[CoreSplit] GPU backend degraded to CPU fallback");
    }

    /**
     * 定期进行健康检查。
     */
    private void maybeCheckHealth() {
        long now = System.currentTimeMillis();
        if (now - lastHealthCheckTime < HEALTH_CHECK_INTERVAL_MS) return;
        lastHealthCheckTime = now;

        if (degradedMode) {
            return;
        }

        GpuBackend backend = activeBackend;
        if (backend == null || backend.getType() == BackendType.CPU_FALLBACK) {
            return;
        }

        try {
            GpuBackendStats stats = backend.getStats();
            if (stats != null && stats.getTaskCount() > 10) {
                double successRate = stats.getSuccessRate();
                if (successRate < 0.8) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] GPU backend success rate too low: {:.1f}%, degrading to CPU",
                            successRate * 100);
                    degradeToCpu();
                }
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.debug("[CoreSplit] GPU health check failed", e);
        }
    }

    public boolean isDegradedMode() {
        return degradedMode;
    }

    public static void submitAsync(Runnable task) {
        INSTANCE.asyncExecutor.submit(task);
    }
}
