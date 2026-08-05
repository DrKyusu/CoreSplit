package com.coresplit.renderlimiter.gpu;

import java.util.List;

public interface GpuBackend {

    BackendType getType();

    String getName();

    boolean isAvailable();

    boolean isInitialized();

    boolean initialize();

    void shutdown();

    List<GpuDeviceDescriptor> getDevices();

    GpuDeviceDescriptor getActiveDevice();

    boolean selectDevice(int deviceIndex);

    GpuBackendStats getStats();

    void resetStats();

    <T> T execute(GpuTask<T> task);

    void executeAsync(GpuTask<Void> task);

    String getLastError();

    boolean supportsFeature(GpuFeature feature);

    enum GpuFeature {
        PARALLEL_COMPUTE,
        GPU_RENDERING,
        SHADER_COMPILATION,
        MEMORY_MANAGEMENT,
        ATOMIC_OPERATIONS,
        TEXTURE_BINDING,
        BUFFER_MANAGEMENT
    }
}
