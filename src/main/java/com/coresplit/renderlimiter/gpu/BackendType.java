package com.coresplit.renderlimiter.gpu;

public enum BackendType {
    OPENCL("OpenCL", "org.lwjgl.opencl.CL"),
    VULKAN("Vulkan", "org.lwjgl.vulkan.VK"),
    CUDA("CUDA", "jcuda.runtime.JCuda"),
    OPENGL("OpenGL", "org.lwjgl.opengl.GL"),
    CPU_FALLBACK("CPU Fallback", null);

    private final String displayName;
    private final String detectionClass;

    BackendType(String displayName, String detectionClass) {
        this.displayName = displayName;
        this.detectionClass = detectionClass;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDetectionClass() {
        return detectionClass;
    }

    public boolean requiresDetection() {
        return detectionClass != null;
    }
}
