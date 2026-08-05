package com.coresplit.renderlimiter.gpu;

import java.util.concurrent.atomic.AtomicLong;

public class GpuBackendStats {

    private final AtomicLong taskCount = new AtomicLong(0);
    private final AtomicLong successfulTasks = new AtomicLong(0);
    private final AtomicLong failedTasks = new AtomicLong(0);
    private final AtomicLong totalExecutionTimeNs = new AtomicLong(0);
    private final AtomicLong maxExecutionTimeNs = new AtomicLong(0);
    private final AtomicLong minExecutionTimeNs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong bytesTransferredToGpu = new AtomicLong(0);
    private final AtomicLong bytesTransferredFromGpu = new AtomicLong(0);
    private final AtomicLong memoryAllocatedBytes = new AtomicLong(0);
    private volatile long initializationTimeNs = 0;
    private volatile long lastTaskTimeNs = 0;

    public void recordTask(long executionTimeNs, boolean success) {
        taskCount.incrementAndGet();
        totalExecutionTimeNs.addAndGet(executionTimeNs);
        lastTaskTimeNs = executionTimeNs;
        updateMinMax(executionTimeNs);
        if (success) {
            successfulTasks.incrementAndGet();
        } else {
            failedTasks.incrementAndGet();
        }
    }

    private void updateMinMax(long executionTimeNs) {
        long currentMin;
        do {
            currentMin = minExecutionTimeNs.get();
            if (executionTimeNs >= currentMin) break;
        } while (!minExecutionTimeNs.compareAndSet(currentMin, executionTimeNs));

        long currentMax;
        do {
            currentMax = maxExecutionTimeNs.get();
            if (executionTimeNs <= currentMax) break;
        } while (!maxExecutionTimeNs.compareAndSet(currentMax, executionTimeNs));
    }

    public void recordTransferToGpu(long bytes) {
        bytesTransferredToGpu.addAndGet(bytes);
    }

    public void recordTransferFromGpu(long bytes) {
        bytesTransferredFromGpu.addAndGet(bytes);
    }

    public void recordMemoryAllocated(long bytes) {
        memoryAllocatedBytes.addAndGet(bytes);
    }

    public void recordMemoryFreed(long bytes) {
        memoryAllocatedBytes.addAndGet(-bytes);
    }

    public void recordInitializationTime(long timeNs) {
        this.initializationTimeNs = timeNs;
    }

    public void reset() {
        taskCount.set(0);
        successfulTasks.set(0);
        failedTasks.set(0);
        totalExecutionTimeNs.set(0);
        maxExecutionTimeNs.set(0);
        minExecutionTimeNs.set(Long.MAX_VALUE);
        bytesTransferredToGpu.set(0);
        bytesTransferredFromGpu.set(0);
        memoryAllocatedBytes.set(0);
        initializationTimeNs = 0;
        lastTaskTimeNs = 0;
    }

    public long getTaskCount() {
        return taskCount.get();
    }

    public long getSuccessfulTasks() {
        return successfulTasks.get();
    }

    public long getFailedTasks() {
        return failedTasks.get();
    }

    public double getSuccessRate() {
        long total = taskCount.get();
        return total == 0 ? 0 : (double) successfulTasks.get() / total;
    }

    public long getTotalExecutionTimeNs() {
        return totalExecutionTimeNs.get();
    }

    public double getAverageExecutionTimeMs() {
        long total = taskCount.get();
        return total == 0 ? 0 : totalExecutionTimeNs.get() / (double) total / 1_000_000;
    }

    public double getMaxExecutionTimeMs() {
        return maxExecutionTimeNs.get() / 1_000_000.0;
    }

    public double getMinExecutionTimeMs() {
        long min = minExecutionTimeNs.get();
        return min == Long.MAX_VALUE ? 0 : min / 1_000_000.0;
    }

    public long getBytesTransferredToGpu() {
        return bytesTransferredToGpu.get();
    }

    public long getBytesTransferredFromGpu() {
        return bytesTransferredFromGpu.get();
    }

    public long getMemoryAllocatedBytes() {
        return memoryAllocatedBytes.get();
    }

    public long getInitializationTimeNs() {
        return initializationTimeNs;
    }

    public double getInitializationTimeMs() {
        return initializationTimeNs / 1_000_000.0;
    }

    public long getLastTaskTimeNs() {
        return lastTaskTimeNs;
    }
}
