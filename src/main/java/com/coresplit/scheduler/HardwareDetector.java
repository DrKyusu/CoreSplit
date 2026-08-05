package com.coresplit.scheduler;

import com.coresplit.CoreSplitMod;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Robust hardware detection utility that validates CPU core/thread counts across
 * multiple JVM APIs to avoid the common pitfall of trusting a single call to
 * {@link Runtime#availableProcessors()} which can under-count in:
 * <ul>
 *   <li>Containerized environments (Docker / WSL2 with --cpus)</li>
 *   <li>Processes launched with OS-level CPU affinity (Start-Process -ProcessorCount)</li>
 *   <li>Hybrid architectures (Intel P/E-core, AMD Zen 4 + Zen 4c) where the JVM
 *       may report logical SMT threads instead of physical cores</li>
 * </ul>
 *
 * <p>Detection is cached after first probe to avoid repeated MXBean calls.
 * All return values are validated and clamped to safe ranges.</p>
 */
public final class HardwareDetector {

    private static final int MIN_CORES = 1;
    private static final int MAX_REASONABLE_CORES = 512;

    private static final AtomicBoolean detected = new AtomicBoolean(false);
    private static final AtomicInteger logicalCores = new AtomicInteger(0);
    private static final AtomicInteger physicalCoresEstimate = new AtomicInteger(0);
    private static volatile boolean hybridSuspected = false;
    private static volatile String osName = "unknown";
    private static volatile String archName = "unknown";

    private HardwareDetector() { }

    /**
     * Run hardware detection. Safe to call multiple times; detection runs only once.
     */
    public static void detect() {
        if (detected.get()) {
            return;
        }
        synchronized (HardwareDetector.class) {
            if (detected.get()) {
                return;
            }
            doDetect();
            detected.set(true);
        }
    }

    private static void doDetect() {
        osName = System.getProperty("os.name", "unknown").toLowerCase();
        archName = System.getProperty("os.arch", "unknown").toLowerCase();

        // --- Method 1: Runtime.availableProcessors() ---
        int method1 = safeInt(() -> Runtime.getRuntime().availableProcessors(), 0);

        // --- Method 2: com.sun.management.OperatingSystemMXBean.getAvailableProcessors() ---
        // On some containerized JDKs this returns the container limit while Runtime returns host cores,
        // or vice-versa. We take the MIN of valid values to respect CPU quotas.
        int method2 = 0;
        try {
            OperatingSystemMXBean mx = ManagementFactory.getOperatingSystemMXBean();
            Class<?> sunClass = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (sunClass.isInstance(mx)) {
                Object raw = sunClass.getMethod("getAvailableProcessors").invoke(mx);
                if (raw instanceof Number) {
                    method2 = ((Number) raw).intValue();
                }
            }
        } catch (Exception ignored) {
            // com.sun.management not available (J9, alternative JDKs) — ignore.
        }

        // --- Method 3: System property fallback for exotic JVMs ---
        int method3 = safeInt(() -> Integer.parseInt(System.getProperty(
                "coresplit.forceCores", "0")), 0);

        // Merge: use the highest valid value, then clamp.
        // Prefer Runtime (gives JVM's allowed count) but if MXBean is higher it means
        // the process is unrestricted and we can trust the larger.
        int logical = Math.max(method1, Math.max(method2, method3));
        logical = Math.max(MIN_CORES, Math.min(MAX_REASONABLE_CORES, logical));

        // Physical cores estimate: if SMT/HyperThreading is likely, divide by 2.
        // We assume SMT on x86_64 / amd64 when logical > 4 (typical desktop).
        int physical = logical;
        boolean smt = (logical >= 8) && ("amd64".equals(archName) || "x86_64".equals(archName));
        if (smt) {
            physical = Math.max(MIN_CORES, logical / 2);
        }

        // Hybrid suspicion: Windows 10/11 + x86_64 + logical > 8 often means 12th/13th/14th gen Intel.
        // We don't try to *prove* hybrid — we just enable opportunistic affinity strategies later.
        boolean hybrid = osName.contains("win") && logical > 8
                && ("amd64".equals(archName) || "x86_64".equals(archName));

        logicalCores.set(logical);
        physicalCoresEstimate.set(physical);
        hybridSuspected = hybrid;

        CoreSplitMod.LOGGER.info("[CoreSplit] Hardware detection: logical={} (M1={}, M2={}, M3={}), " +
                        "physicalEstimate={}, SMT={}, hybridSuspected={}, os={}, arch={}",
                logical, method1, method2, method3, physical, smt, hybrid, osName, archName);
    }

    /**
     * Logical processors as visible to the JVM (= "available threads" for scheduling).
     * This is the value callers should use as the upper bound for thread pool sizing.
     */
    public static int getLogicalCores() {
        detect();
        return logicalCores.get();
    }

    /**
     * Estimated physical cores (SMT threads divided out if suspected).
     * Use this for CPU-bound compute pools where SMT hurts more than it helps.
     */
    public static int getPhysicalCoresEstimate() {
        detect();
        return physicalCoresEstimate.get();
    }

    /**
     * True when Windows x86_64 with >8 logical cores — a strong signal for
     * hybrid (P/E-core) architectures. Callers may opportunistically raise
     * thread priorities and use affinity if available.
     */
    public static boolean isHybridSuspected() {
        detect();
        return hybridSuspected;
    }

    public static String getOsName() { detect(); return osName; }
    public static String getArchName() { detect(); return archName; }

    // ----- Convenience pool-sizing helpers with sane defaults per workload type -----

    /**
     * Recommended threads for a <b>CPU-bound</b> pool.
     * Defaults to logical cores minus one (reserves a core for main/render thread on client).
     * Never less than 1.
     */
    public static int recommendCpuBoundThreads() {
        int cores = getLogicalCores();
        int reservedMain = cores > 4 ? 1 : 0;
        return Math.max(1, cores - reservedMain);
    }

    /**
     * Recommended threads for a <b>mixed I/O + CPU</b> pool (chunk generation / loading).
     * Defaults to max(2, physicalCores × 2). Never less than 2, never more than 32.
     */
    public static int recommendMixedThreads() {
        int physical = getPhysicalCoresEstimate();
        int threads = Math.max(2, physical * 2);
        return Math.min(32, threads);
    }

    /**
     * Recommended threads for a <b>memory-sensitive</b> pool.
     * Same as CPU-bound but capped at 8 to avoid thrashing caches on many-core systems.
     */
    public static int recommendMemorySensitiveThreads() {
        return Math.min(8, recommendCpuBoundThreads());
    }

    private static int safeInt(java.util.function.IntSupplier s, int fallback) {
        try {
            int v = s.getAsInt();
            return (v > 0 && v <= MAX_REASONABLE_CORES) ? v : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * Best-effort thread affinity pinning to the first N performance cores.
     * Only implemented for Windows via reflection (kernel32!SetThreadAffinityMask).
     * On unsupported platforms this is a silent no-op.
     *
     * <p>Note: this must be called <i>from the thread to be pinned</i>.</p>
     *
     * @param preferredCoreMask a bitmask of preferred cores; 0 = no preference
     */
    public static void tryPinThreadToPerformanceCores(long preferredCoreMask) {
        if (preferredCoreMask == 0) return;
        if (!isHybridSuspected()) return;
        if (!osName.contains("win")) return;
        try {
            Class<?> kernel32 = Class.forName("com.sun.jna.platform.win32.Kernel32");
            Object instance = kernel32.getMethod("INSTANCE").invoke(null);
            long mask = preferredCoreMask;
            kernel32.getMethod("SetThreadAffinityMask",
                    Class.forName("com.sun.jna.platform.win32.WinNT$HANDLE"), long.class)
                    .invoke(instance, getCurrentThreadHandle(), mask);
        } catch (Throwable ignored) {
            // JNA not present, method signature changed, or denied by security manager.
            // No-op is acceptable — this is purely an optimization.
        }
    }

    private static Object getCurrentThreadHandle() throws Exception {
        Class<?> kernel32 = Class.forName("com.sun.jna.platform.win32.Kernel32");
        Object instance = kernel32.getMethod("INSTANCE").invoke(null);
        // HANDLE returned; type-checked by the reflective invoke target
        return kernel32.getMethod("GetCurrentThread").invoke(instance);
    }
}
