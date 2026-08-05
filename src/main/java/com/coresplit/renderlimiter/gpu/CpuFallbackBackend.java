package com.coresplit.renderlimiter.gpu;

import java.util.ArrayList;
import java.util.List;

public class CpuFallbackBackend implements GpuBackend {

    private final GpuBackendStats stats = new GpuBackendStats();
    private volatile boolean initialized = false;
    private volatile String lastError = null;

    @Override
    public BackendType getType() {
        return BackendType.CPU_FALLBACK;
    }

    @Override
    public String getName() {
        return "CPU Fallback";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public boolean initialize() {
        long startTime = System.nanoTime();
        try {
            initialized = true;
            stats.recordInitializationTime(System.nanoTime() - startTime);
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    @Override
    public void shutdown() {
        initialized = false;
        stats.reset();
    }

    @Override
    public List<GpuDeviceDescriptor> getDevices() {
        List<GpuDeviceDescriptor> devices = new ArrayList<>();
        devices.add(new GpuDeviceDescriptor(
                0,
                "CPU",
                System.getProperty("java.vendor"),
                System.getProperty("java.version"),
                Runtime.getRuntime().maxMemory(),
                Runtime.getRuntime().availableProcessors(),
                0,
                0,
                false,
                false,
                BackendType.CPU_FALLBACK
        ));
        return devices;
    }

    @Override
    public GpuDeviceDescriptor getActiveDevice() {
        return getDevices().get(0);
    }

    @Override
    public boolean selectDevice(int deviceIndex) {
        return deviceIndex == 0;
    }

    @Override
    public GpuBackendStats getStats() {
        return stats;
    }

    @Override
    public void resetStats() {
        stats.reset();
    }

    @Override
    public <T> T execute(GpuTask<T> task) {
        long startTime = System.nanoTime();
        try {
            T result = task.execute(this);
            stats.recordTask(System.nanoTime() - startTime, true);
            return result;
        } catch (Exception e) {
            lastError = e.getMessage();
            stats.recordTask(System.nanoTime() - startTime, false);
            return null;
        }
    }

    @Override
    public void executeAsync(GpuTask<Void> task) {
        GpuBackendRegistry.submitAsync(() -> execute(task));
    }

    @Override
    public String getLastError() {
        return lastError;
    }

    @Override
    public boolean supportsFeature(GpuFeature feature) {
        return feature == GpuFeature.PARALLEL_COMPUTE || feature == GpuFeature.MEMORY_MANAGEMENT;
    }
}
