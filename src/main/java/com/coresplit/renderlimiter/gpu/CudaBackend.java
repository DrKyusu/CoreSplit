package com.coresplit.renderlimiter.gpu;

import com.coresplit.CoreSplitMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class CudaBackend implements GpuBackend {

    private final GpuBackendStats stats = new GpuBackendStats();
    private volatile boolean initialized = false;
    private volatile boolean available = false;
    private volatile String lastError = null;
    private final Set<GpuFeature> supportedFeatures = new HashSet<>();
    private volatile List<GpuDeviceDescriptor> devices = new ArrayList<>();
    private volatile GpuDeviceDescriptor activeDevice = null;

    public CudaBackend() {
        detectAvailability();
    }

    private void detectAvailability() {
        try {
            Class.forName("jcuda.runtime.JCuda");
            available = true;
            supportedFeatures.add(GpuFeature.PARALLEL_COMPUTE);
            supportedFeatures.add(GpuFeature.MEMORY_MANAGEMENT);
            supportedFeatures.add(GpuFeature.ATOMIC_OPERATIONS);
            supportedFeatures.add(GpuFeature.BUFFER_MANAGEMENT);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            available = false;
            lastError = "CUDA/JCuda API not available: " + e.getMessage();
        }
    }

    @Override
    public BackendType getType() {
        return BackendType.CUDA;
    }

    @Override
    public String getName() {
        return "CUDA";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public boolean initialize() {
        if (!available) {
            return false;
        }

        long startTime = System.nanoTime();
        try {
            Class<?> jcudaClass = Class.forName("jcuda.runtime.JCuda");
            java.lang.reflect.Method cudaInitMethod = jcudaClass.getMethod("cudaInit", int.class);
            cudaInitMethod.invoke(null, 0);

            detectDevices();
            if (!devices.isEmpty()) {
                activeDevice = devices.get(0);
            }

            initialized = true;
            stats.recordInitializationTime(System.nanoTime() - startTime);
            CoreSplitMod.LOGGER.info("[CoreSplit] CUDA backend initialized with {} devices", devices.size());
            return true;
        } catch (Exception e) {
            lastError = "CUDA initialization failed: " + e.getMessage();
            CoreSplitMod.LOGGER.error("[CoreSplit] CUDA initialization error: {}", e.getMessage());
            return false;
        }
    }

    private void detectDevices() {
        devices = new ArrayList<>();
        try {
            Class<?> jcudaClass = Class.forName("jcuda.runtime.JCuda");
            Class<?> cudaDevicePropClass = Class.forName("jcuda.runtime.CudaDeviceProp");

            java.lang.reflect.Method cudaGetDeviceCountMethod = jcudaClass.getMethod("cudaGetDeviceCount", int[].class);
            int[] count = new int[1];
            cudaGetDeviceCountMethod.invoke(null, count);

            int deviceCount = count[0];
            for (int i = 0; i < deviceCount; i++) {
                java.lang.reflect.Constructor<?> constructor = cudaDevicePropClass.getDeclaredConstructor();
                Object prop = constructor.newInstance();

                java.lang.reflect.Method cudaGetDevicePropertiesMethod = jcudaClass.getMethod("cudaGetDeviceProperties", cudaDevicePropClass, int.class);
                cudaGetDevicePropertiesMethod.invoke(null, prop, i);

                devices.add(createDeviceDescriptor(prop, i));
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.debug("[CoreSplit] CUDA device detection error: {}", e.getMessage());
            devices.add(new GpuDeviceDescriptor(
                    0,
                    "CUDA Device",
                    "Unknown",
                    "Unknown",
                    0,
                    0,
                    0,
                    0,
                    false,
                    true,
                    BackendType.CUDA
            ));
        }
    }

    private GpuDeviceDescriptor createDeviceDescriptor(Object prop, int index) {
        try {
            Class<?> cudaDevicePropClass = Class.forName("jcuda.runtime.CudaDeviceProp");

            java.lang.reflect.Field nameField = cudaDevicePropClass.getField("name");
            String name = (String) nameField.get(prop);

            java.lang.reflect.Field vendorIdField = cudaDevicePropClass.getField("vendorID");
            int vendorId = vendorIdField.getInt(prop);

            java.lang.reflect.Field driverVersionField = cudaDevicePropClass.getField("driverVersion");
            int driverVersion = driverVersionField.getInt(prop);

            java.lang.reflect.Field totalGlobalMemField = cudaDevicePropClass.getField("totalGlobalMem");
            long memorySize = totalGlobalMemField.getLong(prop);

            java.lang.reflect.Field multiProcessorCountField = cudaDevicePropClass.getField("multiProcessorCount");
            int computeUnits = multiProcessorCountField.getInt(prop);

            java.lang.reflect.Field clockRateField = cudaDevicePropClass.getField("clockRate");
            int coreClock = clockRateField.getInt(prop) / 1000;

            java.lang.reflect.Field integratedField = cudaDevicePropClass.getField("integrated");
            boolean isIntegrated = integratedField.getBoolean(prop);

            java.lang.reflect.Field maxThreadsPerMultiProcessorField = cudaDevicePropClass.getField("maxThreadsPerMultiProcessor");
            int threadsPerSM = maxThreadsPerMultiProcessorField.getInt(prop);

            String vendor = switch (vendorId) {
                case 0x10DE -> "NVIDIA";
                default -> "Unknown";
            };

            return new GpuDeviceDescriptor(index, name, vendor, String.valueOf(driverVersion),
                    memorySize, computeUnits, coreClock, 0, isIntegrated, !isIntegrated, BackendType.CUDA);
        } catch (Exception e) {
            return new GpuDeviceDescriptor(index, "Unknown CUDA Device", "Unknown", "Unknown",
                    0, 0, 0, 0, false, true, BackendType.CUDA);
        }
    }

    @Override
    public void shutdown() {
        if (!initialized) return;

        try {
            Class<?> jcudaClass = Class.forName("jcuda.runtime.JCuda");
            java.lang.reflect.Method cudaDeviceResetMethod = jcudaClass.getMethod("cudaDeviceReset");
            cudaDeviceResetMethod.invoke(null);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] CUDA shutdown error: {}", e.getMessage());
        }

        initialized = false;
        stats.reset();
    }

    @Override
    public List<GpuDeviceDescriptor> getDevices() {
        return devices;
    }

    @Override
    public GpuDeviceDescriptor getActiveDevice() {
        return activeDevice;
    }

    @Override
    public boolean selectDevice(int deviceIndex) {
        if (!available || deviceIndex < 0 || deviceIndex >= devices.size()) {
            return false;
        }

        try {
            Class<?> jcudaClass = Class.forName("jcuda.runtime.JCuda");
            java.lang.reflect.Method cudaSetDeviceMethod = jcudaClass.getMethod("cudaSetDevice", int.class);
            cudaSetDeviceMethod.invoke(null, deviceIndex);
            activeDevice = devices.get(deviceIndex);
            return true;
        } catch (Exception e) {
            lastError = "Failed to select CUDA device: " + e.getMessage();
            return false;
        }
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
        return supportedFeatures.contains(feature);
    }
}
