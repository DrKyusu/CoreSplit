package com.coresplit.monitoring;

import com.coresplit.CoreSplitMod;

/**
 * 设备性能档案探测器。
 *
 * <p>启动时探测 CPU 核心数、可用内存、GPU 等级，输出推荐的模块预设。
 * 用于动态性能调节的初始配置建议。
 */
public class DeviceProfile {

    public enum DeviceTier {
        LOW("Low-end device: minimal preset recommended", "minimal"),
        MID("Mid-range device: balanced preset recommended", "balanced"),
        HIGH("High-end device: quality preset recommended", "quality"),
        UNKNOWN("Device profile unknown: balanced preset recommended", "balanced");

        public final String description;
        public final String recommendedPreset;

        DeviceTier(String description, String recommendedPreset) {
            this.description = description;
            this.recommendedPreset = recommendedPreset;
        }
    }

    // 分级阈值常量
    private static final int LOW_CORE_THRESHOLD = 4;
    private static final int HIGH_CORE_THRESHOLD = 8;
    private static final long LOW_MEMORY_MB = 4096;
    private static final long HIGH_MEMORY_MB = 16384;

    private volatile DeviceTier detectedTier = DeviceTier.UNKNOWN;
    private volatile int cpuCores = 0;
    private volatile int physicalCoresEstimate = 0;
    private volatile boolean hybridCpu = false;
    private volatile String cpuArch = "unknown";
    private volatile String osName = "unknown";
    private volatile long maxMemoryMb = 0;
    private volatile String detectedGpu = "unknown";

    /**
     * Detect device performance profile. Uses HardwareDetector for robust multi-probe
     * CPU/core validation (v2.5). Previously relied solely on
     * Runtime.availableProcessors() which can under-count in containers / with affinity.
     */
    public DeviceTier detect() {
        // Prime HardwareDetector so logical/physical estimates are available.
        com.coresplit.scheduler.HardwareDetector.detect();
        cpuCores = com.coresplit.scheduler.HardwareDetector.getLogicalCores();
        physicalCoresEstimate = com.coresplit.scheduler.HardwareDetector.getPhysicalCoresEstimate();
        hybridCpu = com.coresplit.scheduler.HardwareDetector.isHybridSuspected();
        cpuArch = com.coresplit.scheduler.HardwareDetector.getArchName();
        osName = com.coresplit.scheduler.HardwareDetector.getOsName();

        maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        // GPU 探测：客户端通过 Minecraft.getInstance().getGpuUtilization() 获取
        // 服务端无法获取 GPU，不影响分级（服务端不渲染）
        detectedGpu = detectGpu();

        // 综合评分：CPU 核心数 + 内存
        int score = 0;
        if (cpuCores >= HIGH_CORE_THRESHOLD) score += 2;
        else if (cpuCores >= LOW_CORE_THRESHOLD) score += 1;

        if (maxMemoryMb >= HIGH_MEMORY_MB) score += 2;
        else if (maxMemoryMb >= LOW_MEMORY_MB) score += 1;

        if (score >= 3) {
            detectedTier = DeviceTier.HIGH;
        } else if (score >= 1) {
            detectedTier = DeviceTier.MID;
        } else {
            detectedTier = DeviceTier.LOW;
        }

        CoreSplitMod.LOGGER.info("[CoreSplit] Device profile: {} (logical={}, physicalEst={}, hybrid={}, arch={}, os={}, maxMem={}MB, gpu={})",
                detectedTier.name(), cpuCores, physicalCoresEstimate, hybridCpu, cpuArch, osName, maxMemoryMb, detectedGpu);
        CoreSplitMod.LOGGER.info("[CoreSplit] Recommendation: {}", detectedTier.description);

        return detectedTier;
    }

    /**
     * GPU 探测，服务端返回 "server-side"。
     */
    private String detectGpu() {
        try {
            // 尝试客户端 API（仅客户端环境可用）
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object instance = mcClass.getMethod("getInstance").invoke(null);
            if (instance != null) {
                try {
                    Object gpu = mcClass.getMethod("getGpuUtilization").invoke(instance);
                    return gpu != null ? gpu.toString() : "client-gpu-detected";
                } catch (NoSuchMethodException e) {
                    return "client-gpu-unavailable";
                }
            }
        } catch (ClassNotFoundException e) {
            // 服务端环境，无 Minecraft 客户端类
            return "server-side";
        } catch (Exception e) {
            return "detection-failed";
        }
        return "unknown";
    }

    public DeviceTier getDetectedTier() { return detectedTier; }
    public int getCpuCores() { return cpuCores; }
    public int getPhysicalCoresEstimate() { return physicalCoresEstimate; }
    public boolean isHybridCpu() { return hybridCpu; }
    public String getCpuArch() { return cpuArch; }
    public String getOsName() { return osName; }
    public long getMaxMemoryMb() { return maxMemoryMb; }
    public String getDetectedGpu() { return detectedGpu; }
    public String getRecommendedPreset() { return detectedTier.recommendedPreset; }

    /**
     * 生成设备档案报告段。
     */
    public String generateReportSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Device Profile ===\n");
        sb.append(String.format("Tier: %s\n", detectedTier.name()));
        sb.append(String.format("CPU Logical Cores: %d\n", cpuCores));
        sb.append(String.format("CPU Physical Cores (est.): %d\n", physicalCoresEstimate));
        sb.append(String.format("Hybrid Arch Suspected: %s\n", hybridCpu));
        sb.append(String.format("CPU Architecture: %s\n", cpuArch));
        sb.append(String.format("OS: %s\n", osName));
        sb.append(String.format("Max Memory: %dMB\n", maxMemoryMb));
        sb.append(String.format("GPU: %s\n", detectedGpu));
        sb.append(String.format("Recommended Preset: %s\n", detectedTier.recommendedPreset));
        return sb.toString();
    }
}
