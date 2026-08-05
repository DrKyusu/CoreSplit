package com.coresplit.renderlimiter.gpu;

import com.coresplit.CoreSplitMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class OpenClBackend implements GpuBackend {

    private final GpuBackendStats stats = new GpuBackendStats();
    private volatile boolean initialized = false;
    private volatile boolean available = false;
    private volatile String lastError = null;
    private final Set<GpuFeature> supportedFeatures = new HashSet<>();
    private volatile List<GpuDeviceDescriptor> devices = new ArrayList<>();
    private volatile GpuDeviceDescriptor activeDevice = null;

    public OpenClBackend() {
        detectAvailability();
    }

    private void detectAvailability() {
        try {
            Class.forName("org.lwjgl.opencl.CL");
            available = true;
            supportedFeatures.add(GpuFeature.PARALLEL_COMPUTE);
            supportedFeatures.add(GpuFeature.MEMORY_MANAGEMENT);
            supportedFeatures.add(GpuFeature.ATOMIC_OPERATIONS);
            supportedFeatures.add(GpuFeature.BUFFER_MANAGEMENT);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            available = false;
            lastError = "OpenCL API not available: " + e.getMessage();
        }
    }

    @Override
    public BackendType getType() {
        return BackendType.OPENCL;
    }

    @Override
    public String getName() {
        return "OpenCL";
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
            detectDevices();
            if (!devices.isEmpty()) {
                activeDevice = devices.get(0);
            }

            initialized = true;
            stats.recordInitializationTime(System.nanoTime() - startTime);
            CoreSplitMod.LOGGER.info("[CoreSplit] OpenCL backend initialized with {} devices", devices.size());
            return true;
        } catch (Exception e) {
            lastError = "OpenCL initialization failed: " + e.getMessage();
            CoreSplitMod.LOGGER.error("[CoreSplit] OpenCL initialization error: {}", e.getMessage());
            return false;
        }
    }

    private void detectDevices() {
        devices = new ArrayList<>();
        try {
            Class<?> clPlatformClass = Class.forName("org.lwjgl.opencl.CLPlatform");
            java.lang.reflect.Method getPlatformsMethod = clPlatformClass.getMethod("getPlatforms");
            Object platforms = getPlatformsMethod.invoke(null);

            Class<?> pointerBufferClass = Class.forName("org.lwjgl.PointerBuffer");
            java.lang.reflect.Method remainingMethod = pointerBufferClass.getMethod("remaining");
            int platformCount = (int) remainingMethod.invoke(platforms);

            for (int i = 0; i < platformCount; i++) {
                java.lang.reflect.Method getMethod = pointerBufferClass.getMethod("get", int.class);
                Object platform = getMethod.invoke(platforms, i);

                java.lang.reflect.Method getDevicesMethod = clPlatformClass.getMethod("getDevices", int.class);
                Object clDevices = getDevicesMethod.invoke(platform, 4);

                int deviceCount = (int) remainingMethod.invoke(clDevices);
                for (int j = 0; j < deviceCount; j++) {
                    Object device = getMethod.invoke(clDevices, j);
                    devices.add(createDeviceDescriptor(device, j));
                }
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.debug("[CoreSplit] OpenCL device detection error: {}", e.getMessage());
            devices.add(new GpuDeviceDescriptor(
                    0,
                    "OpenCL Device",
                    "Unknown",
                    "Unknown",
                    0,
                    0,
                    0,
                    0,
                    false,
                    true,
                    BackendType.OPENCL
            ));
        }
    }

    private GpuDeviceDescriptor createDeviceDescriptor(Object device, int index) {
        try {
            Class<?> clDeviceClass = Class.forName("org.lwjgl.opencl.CLDevice");

            java.lang.reflect.Method getNameMethod = clDeviceClass.getMethod("getName");
            String name = (String) getNameMethod.invoke(device);

            java.lang.reflect.Method getVendorMethod = clDeviceClass.getMethod("getVendor");
            String vendor = (String) getVendorMethod.invoke(device);

            java.lang.reflect.Method getDriverVersionMethod = clDeviceClass.getMethod("getDriverVersion");
            String driverVersion = (String) getDriverVersionMethod.invoke(device);

            java.lang.reflect.Method getMaxMemAllocSizeMethod = clDeviceClass.getMethod("getMaxMemAllocSize");
            long memorySize = (long) getMaxMemAllocSizeMethod.invoke(device);

            java.lang.reflect.Method getMaxComputeUnitsMethod = clDeviceClass.getMethod("getMaxComputeUnits");
            int computeUnits = (int) getMaxComputeUnitsMethod.invoke(device);

            java.lang.reflect.Method getMaxClockFrequencyMethod = clDeviceClass.getMethod("getMaxClockFrequency");
            int coreClock = (int) getMaxClockFrequencyMethod.invoke(device);

            java.lang.reflect.Method getTypeMethod = clDeviceClass.getMethod("getType");
            int type = (int) getTypeMethod.invoke(device);
            boolean isIntegrated = (type & 1) != 0;
            boolean isDedicated = (type & 2) != 0;

            return new GpuDeviceDescriptor(index, name, vendor, driverVersion, memorySize,
                    computeUnits, coreClock, 0, isIntegrated, isDedicated, BackendType.OPENCL);
        } catch (Exception e) {
            return new GpuDeviceDescriptor(index, "Unknown OpenCL Device", "Unknown", "Unknown",
                    0, 0, 0, 0, false, true, BackendType.OPENCL);
        }
    }

    @Override
    public void shutdown() {
        if (!initialized) return;

        initialized = false;
        stats.reset();
        CoreSplitMod.LOGGER.debug("[CoreSplit] OpenCL backend shutdown");
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
        if (deviceIndex >= 0 && deviceIndex < devices.size()) {
            activeDevice = devices.get(deviceIndex);
            return true;
        }
        return false;
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
