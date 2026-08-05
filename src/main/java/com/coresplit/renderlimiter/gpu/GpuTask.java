package com.coresplit.renderlimiter.gpu;

public interface GpuTask<T> {
    T execute(GpuBackend backend);
}
