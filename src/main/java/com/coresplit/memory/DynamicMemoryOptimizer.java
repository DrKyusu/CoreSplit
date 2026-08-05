package com.coresplit.memory;

import com.coresplit.CoreSplitMod;
import com.coresplit.CoreSplitClientMod;
import com.coresplit.scheduler.ResourceMonitor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态内存优化器。
 *
 * <p>根据游戏负载动态调整内存占用：
 * <ul>
 *   <li>低负载（高FPS + 低CPU）：深度释放内存，对象池缩小，缓存清空，主动 GC</li>
 *   <li>中负载：正常模式，保留适量缓存</li>
 *   <li>高负载：扩大缓存和对象池，避免GC影响帧率</li>
 * </ul>
 *
 * <p>低负载判定条件（同时满足）：
 * <ul>
 *   <li>FPS > 目标 FPS × 1.2（远高于目标帧率）</li>
 *   <li>CPU 使用率 < 30%</li>
 *   <li>连续 10 秒处于低负载状态</li>
 * </ul>
 *
 * <p>目标：低负载场景下将堆内存使用降至最低，释放给操作系统。
 * 
 * <p>优化：所有 GC 和缓存清理操作均在异步线程执行，避免阻塞渲染线程导致掉帧。
 */
public class DynamicMemoryOptimizer {

    public enum MemoryLevel {
        NORMAL,
        MINIMAL
    }

    private static final long CHECK_INTERVAL_MS = 2000L;
    private static final long LOW_LOAD_HOLD_TIME_MS = 10_000L;
    private static final long HIGH_LOAD_RECOVERY_TIME_MS = 2000L;
    private static final long GC_INTERVAL_MS = 30_000L;
    private static final float LOW_LOAD_CPU_THRESHOLD = 0.30f;
    private static final float LOW_LOAD_FPS_MULTIPLIER = 1.2f;

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private volatile MemoryLevel currentLevel = MemoryLevel.NORMAL;

    private volatile long lastCheckTime = 0;
    private volatile long lowLoadStartTime = 0;
    private volatile long highLoadStartTime = 0;
    private volatile long lastGcTime = 0;
    private final AtomicLong totalGcRuns = new AtomicLong(0);
    private final AtomicLong memoryReducedBytes = new AtomicLong(0);

    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CoreSplit-MemoryCleaner");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final AtomicBoolean cleanupInProgress = new AtomicBoolean(false);

    private static volatile DynamicMemoryOptimizer instance;

    public static DynamicMemoryOptimizer getInstance() {
        DynamicMemoryOptimizer result = instance;
        if (result == null) {
            synchronized (DynamicMemoryOptimizer.class) {
                result = instance;
                if (result == null) {
                    result = new DynamicMemoryOptimizer();
                    instance = result;
                }
            }
        }
        return result;
    }

    private DynamicMemoryOptimizer() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
    }

    public void onTick() {
        if (!enabled.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) return;
        lastCheckTime = now;

        boolean isLowLoad = detectLowLoad();

        if (isLowLoad) {
            if (lowLoadStartTime == 0) {
                lowLoadStartTime = now;
                highLoadStartTime = 0;
            } else if (now - lowLoadStartTime >= LOW_LOAD_HOLD_TIME_MS
                    && currentLevel != MemoryLevel.MINIMAL) {
                enterMinimalMode();
            }
        } else {
            if (highLoadStartTime == 0) {
                highLoadStartTime = now;
                lowLoadStartTime = 0;
            } else if (now - highLoadStartTime >= HIGH_LOAD_RECOVERY_TIME_MS
                    && currentLevel != MemoryLevel.NORMAL) {
                enterNormalMode();
            }
        }

        if (currentLevel == MemoryLevel.MINIMAL
                && now - lastGcTime >= GC_INTERVAL_MS) {
            triggerCleanup();
            lastGcTime = now;
        }
    }

    private boolean detectLowLoad() {
        ResourceMonitor monitor = CoreSplitClientMod.getResourceMonitor();
        if (monitor == null) return false;

        double cpuUsage = monitor.getCurrentCpuUsage();
        if (cpuUsage > LOW_LOAD_CPU_THRESHOLD) return false;

        float currentFps = getCurrentFps();
        int targetFps = getTargetFps();
        if (currentFps <= 0 || targetFps <= 0) return false;

        return currentFps >= targetFps * LOW_LOAD_FPS_MULTIPLIER;
    }

    private float getCurrentFps() {
        try {
            com.coresplit.governor.PerformanceGovernor governor =
                    CoreSplitClientMod.getGovernor();
            if (governor != null && governor.isInitialized()) {
                float fps = governor.getCurrentFps();
                if (fps > 0) return fps;
            }
        } catch (Exception ignored) {
        }
        return 60.0f;
    }

    private int getTargetFps() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null && mc.options.framerateLimit() != null) {
                int limit = mc.options.framerateLimit().get();
                return limit > 0 ? limit : 60;
            }
            return 60;
        } catch (Exception e) {
            return 60;
        }
    }

    private void enterMinimalMode() {
        currentLevel = MemoryLevel.MINIMAL;
        CoreSplitMod.LOGGER.info("[CoreSplit] DynamicMemoryOptimizer: Entering MINIMAL mode (low load detected)");

        asyncCleanup(true);
    }

    private void enterNormalMode() {
        currentLevel = MemoryLevel.NORMAL;
        CoreSplitMod.LOGGER.info("[CoreSplit] DynamicMemoryOptimizer: Restoring NORMAL mode (high load detected)");

        try {
            MemoryOptimizer memory = CoreSplitMod.getMemoryOptimizer();
            if (memory != null) {
                memory.normalEvict();
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Normal mode restore failed", e);
        }
    }

    private void triggerCleanup() {
        asyncCleanup(false);
    }

    /**
     * 异步执行内存清理操作，避免阻塞渲染线程导致掉帧。
     *
     * @param fullCleanup 是否执行完整清理（进入MINIMAL模式时）
     */
    private void asyncCleanup(boolean fullCleanup) {
        if (!cleanupInProgress.compareAndSet(false, true)) {
            return;
        }

        cleanupExecutor.submit(() -> {
            try {
                long beforeBytes = getCurrentMemoryUsed();

                if (fullCleanup) {
                    try {
                        MemoryOptimizer memory = CoreSplitMod.getMemoryOptimizer();
                        if (memory != null) {
                            memory.aggressiveEvict();
                            memory.getEntityDataPool().clear();
                            memory.getByteBufferPool().clear();
                        }
                    } catch (Exception e) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Minimal mode cache clear failed", e);
                    }
                }

                System.gc();

                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                System.gc();

                long afterBytes = getCurrentMemoryUsed();
                long saved = beforeBytes - afterBytes;
                if (saved > 0) {
                    memoryReducedBytes.addAndGet(saved);
                }
                totalGcRuns.incrementAndGet();

                CoreSplitMod.LOGGER.debug("[CoreSplit] Async GC cleanup: {}MB -> {}MB (saved {}MB)",
                        beforeBytes / (1024 * 1024), afterBytes / (1024 * 1024), saved / (1024 * 1024));
            } catch (Exception e) {
                CoreSplitMod.LOGGER.debug("[CoreSplit] Async GC cleanup failed", e);
            } finally {
                cleanupInProgress.set(false);
            }
        });
    }

    private long getCurrentMemoryUsed() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public MemoryLevel getCurrentLevel() {
        return currentLevel;
    }

    public boolean isMinimalMode() {
        return currentLevel == MemoryLevel.MINIMAL;
    }

    public long getTotalGcRuns() {
        return totalGcRuns.get();
    }

    public long getMemoryReducedBytes() {
        return memoryReducedBytes.get();
    }

    public String getMemoryReducedFormatted() {
        long mb = memoryReducedBytes.get() / (1024 * 1024);
        return mb + " MB";
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (!enabled && currentLevel == MemoryLevel.MINIMAL) {
            enterNormalMode();
        }
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public float getCurrentCpuUsage() {
        ResourceMonitor monitor = CoreSplitClientMod.getResourceMonitor();
        return monitor != null ? (float) monitor.getCurrentCpuUsage() : 0f;
    }

    public void shutdown() {
        cleanupExecutor.shutdown();
    }
}
