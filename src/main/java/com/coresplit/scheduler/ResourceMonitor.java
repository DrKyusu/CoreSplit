package com.coresplit.scheduler;

import com.coresplit.CoreSplitMod;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ResourceMonitor {

    private static final int HISTORY_SIZE = 60;
    private static final long UPDATE_INTERVAL_MS = 500;

    private final OperatingSystemMXBean osBean;
    private final ThreadMXBean threadBean;
    
    private final double[] cpuHistory = new double[HISTORY_SIZE];
    private final long[] memoryHistory = new long[HISTORY_SIZE];
    private volatile int historyIndex = 0;
    
    private volatile double currentCpuUsage = 0.0;
    private volatile long currentMemoryUsed = 0;
    private volatile long currentMemoryTotal = 0;
    private volatile int activeThreadCount = 0;
    private volatile int peakThreadCount = 0;
    
    private volatile long lastUpdateTime = 0;
    
    private final AtomicLong sampleCount = new AtomicLong(0);
    
    private volatile boolean enabled = true;

    private final AtomicReference<CpuMethod> cpuMethod = new AtomicReference<>(CpuMethod.UNKNOWN);
    
    private Method getProcessCpuLoadMethod;
    private Method getProcessCpuTimeMethod;
    private Method getCpuLoadMethod;
    
    private volatile long previousProcessCpuTime = 0;
    private volatile long previousNanoTime = 0;

    private volatile long threadCpuTimePrev = 0;
    private volatile long threadTimePrevNano = 0;
    
    private final Object historyLock = new Object();

    private enum CpuMethod {
        UNKNOWN,
        PROCESS_CPU_LOAD,
        PROCESS_CPU_TIME,
        OS_BEAN_CPU_LOAD,
        THREAD_CPU_TIME,
        FALLBACK
    }

    public ResourceMonitor() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        
        initCpuDetectionMethods();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] ResourceMonitor initialized (CPU detection method: {})", cpuMethod.get());
    }

    private void initCpuDetectionMethods() {
        try {
            getProcessCpuLoadMethod = osBean.getClass().getMethod("getProcessCpuLoad");
            getProcessCpuLoadMethod.setAccessible(true);
        } catch (Exception e) {
            getProcessCpuLoadMethod = null;
        }
        
        try {
            getProcessCpuTimeMethod = osBean.getClass().getMethod("getProcessCpuTime");
            getProcessCpuTimeMethod.setAccessible(true);
        } catch (Exception e) {
            getProcessCpuTimeMethod = null;
        }
        
        try {
            getCpuLoadMethod = OperatingSystemMXBean.class.getMethod("getCpuLoad");
        } catch (Exception e) {
            getCpuLoadMethod = null;
        }

        if (getProcessCpuLoadMethod != null) {
            cpuMethod.set(CpuMethod.PROCESS_CPU_LOAD);
        } else if (getProcessCpuTimeMethod != null) {
            cpuMethod.set(CpuMethod.PROCESS_CPU_TIME);
        } else if (threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled()) {
            cpuMethod.set(CpuMethod.THREAD_CPU_TIME);
        } else if (getCpuLoadMethod != null) {
            cpuMethod.set(CpuMethod.OS_BEAN_CPU_LOAD);
        } else {
            cpuMethod.set(CpuMethod.FALLBACK);
        }
    }

    public void update() {
        if (!enabled) return;
        
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return;
        lastUpdateTime = now;

        updateCpuUsage();
        updateMemoryUsage();
        updateThreadCount();
        
        recordHistory();
        sampleCount.incrementAndGet();
    }

    private void updateCpuUsage() {
        double usage = switch (cpuMethod.get()) {
            case PROCESS_CPU_LOAD -> readProcessCpuLoad();
            case PROCESS_CPU_TIME -> readProcessCpuTime();
            case THREAD_CPU_TIME -> readThreadCpuTime();
            case OS_BEAN_CPU_LOAD -> readOsBeanCpuLoad();
            default -> 0.0;
        };
        
        if (usage < 0 || Double.isNaN(usage) || Double.isInfinite(usage)) {
            if (cpuMethod.get() != CpuMethod.FALLBACK) {
                upgradeCpuMethod();
                usage = 0.0;
            } else {
                usage = 0.0;
            }
        }
        
        currentCpuUsage = Math.max(0.0, Math.min(1.0, usage));
    }

    private void upgradeCpuMethod() {
        CpuMethod current = cpuMethod.get();
        CpuMethod next = switch (current) {
            case PROCESS_CPU_LOAD -> CpuMethod.PROCESS_CPU_TIME;
            case PROCESS_CPU_TIME -> CpuMethod.THREAD_CPU_TIME;
            case THREAD_CPU_TIME -> CpuMethod.OS_BEAN_CPU_LOAD;
            case OS_BEAN_CPU_LOAD -> CpuMethod.FALLBACK;
            default -> CpuMethod.FALLBACK;
        };
        if (next != current) {
            cpuMethod.set(next);
            CoreSplitMod.LOGGER.debug("[CoreSplit] CPU detection method downgraded: {} -> {}", current, next);
        }
    }

    private double readProcessCpuLoad() {
        if (getProcessCpuLoadMethod == null) return -1;
        try {
            Double load = (Double) getProcessCpuLoadMethod.invoke(osBean);
            if (load != null && load >= 0 && load <= 1.0) {
                return load;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private double readProcessCpuTime() {
        if (getProcessCpuTimeMethod == null) return -1;
        try {
            Long cpuTimeObj = (Long) getProcessCpuTimeMethod.invoke(osBean);
            if (cpuTimeObj == null) return -1;
            long cpuTime = cpuTimeObj;
            long nanoTime = System.nanoTime();

            if (previousNanoTime > 0 && nanoTime > previousNanoTime && previousProcessCpuTime > 0) {
                long cpuDelta = cpuTime - previousProcessCpuTime;
                long nanoDelta = nanoTime - previousNanoTime;
                
                if (cpuDelta >= 0 && nanoDelta > 0) {
                    int processors = osBean.getAvailableProcessors();
                    double usage = (double) cpuDelta / nanoDelta / processors;
                    previousProcessCpuTime = cpuTime;
                    previousNanoTime = nanoTime;
                    return Math.min(1.0, usage);
                }
            }
            
            previousProcessCpuTime = cpuTime;
            previousNanoTime = nanoTime;
            return 0.0;
        } catch (Exception e) {
            return -1;
        }
    }

    private double readThreadCpuTime() {
        try {
            if (!threadBean.isThreadCpuTimeSupported()) return -1;
            if (!threadBean.isThreadCpuTimeEnabled()) {
                threadBean.setThreadCpuTimeEnabled(true);
            }
            
            long totalCpuTime = 0;
            long[] threadIds = threadBean.getAllThreadIds();
            for (long id : threadIds) {
                long time = threadBean.getThreadCpuTime(id);
                if (time > 0) totalCpuTime += time;
            }
            
            long now = System.nanoTime();
            if (threadTimePrevNano > 0 && now > threadTimePrevNano && threadCpuTimePrev > 0) {
                long deltaCpu = totalCpuTime - threadCpuTimePrev;
                long deltaNano = now - threadTimePrevNano;
                int processors = osBean.getAvailableProcessors();
                double usage = (double) deltaCpu / deltaNano / processors;
                threadCpuTimePrev = totalCpuTime;
                threadTimePrevNano = now;
                return Math.min(1.0, Math.max(0.0, usage));
            }
            
            threadCpuTimePrev = totalCpuTime;
            threadTimePrevNano = now;
            return 0.0;
        } catch (Exception e) {
            return -1;
        }
    }

    private double readOsBeanCpuLoad() {
        if (getCpuLoadMethod == null) return -1;
        try {
            Double load = (Double) getCpuLoadMethod.invoke(osBean);
            if (load != null && load >= 0 && load <= 1.0) {
                return load;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void updateMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        currentMemoryUsed = runtime.totalMemory() - runtime.freeMemory();
        currentMemoryTotal = runtime.maxMemory();
    }

    private void updateThreadCount() {
        activeThreadCount = threadBean.getThreadCount();
        peakThreadCount = Math.max(peakThreadCount, activeThreadCount);
    }

    private void recordHistory() {
        synchronized (historyLock) {
            int idx = historyIndex % HISTORY_SIZE;
            cpuHistory[idx] = currentCpuUsage;
            memoryHistory[idx] = currentMemoryUsed;
            historyIndex++;
        }
    }

    public double getCurrentCpuUsage() {
        return currentCpuUsage;
    }

    public double getAverageCpuUsage() {
        synchronized (historyLock) {
            int count = Math.min(historyIndex, HISTORY_SIZE);
            if (count == 0) return 0.0;
            
            double sum = 0;
            for (int i = 0; i < count; i++) {
                sum += cpuHistory[i];
            }
            return sum / count;
        }
    }

    public long getCurrentMemoryUsed() {
        return currentMemoryUsed;
    }

    public long getCurrentMemoryTotal() {
        return currentMemoryTotal;
    }

    public double getMemoryUsagePercent() {
        if (currentMemoryTotal == 0) return 0.0;
        return (double) currentMemoryUsed / currentMemoryTotal;
    }

    public int getActiveThreadCount() {
        return activeThreadCount;
    }

    public int getPeakThreadCount() {
        return peakThreadCount;
    }

    public int getAvailableProcessors() {
        return osBean.getAvailableProcessors();
    }

    public long getSampleCount() {
        return sampleCount.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void resetPeakThreadCount() {
        peakThreadCount = activeThreadCount;
    }

    public String getCpuUsageFormatted() {
        return String.format("%.1f%%", currentCpuUsage * 100);
    }

    public String getMemoryUsageFormatted() {
        return String.format("%.2f GB / %.2f GB", 
                bytesToGb(currentMemoryUsed), 
                bytesToGb(currentMemoryTotal));
    }

    public String getMemoryUsagePercentFormatted() {
        return String.format("%.1f%%", getMemoryUsagePercent() * 100);
    }

    public String getCpuMethodName() {
        return cpuMethod.get().name();
    }

    private double bytesToGb(long bytes) {
        return bytes / (1024.0 * 1024.0 * 1024.0);
    }
}
