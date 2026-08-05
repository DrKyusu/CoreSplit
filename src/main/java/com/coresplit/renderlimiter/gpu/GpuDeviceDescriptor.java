package com.coresplit.renderlimiter.gpu;

public record GpuDeviceDescriptor(
        int index,
        String name,
        String vendor,
        String driverVersion,
        long memorySizeBytes,
        int computeUnits,
        int coreClockMhz,
        int memoryClockMhz,
        boolean isIntegrated,
        boolean isDedicated,
        BackendType backendType
) {
    public long getMemorySizeMb() {
        return memorySizeBytes / (1024 * 1024);
    }

    public boolean isRecommended() {
        return isDedicated && memorySizeBytes > 1_073_741_824L;
    }
}
