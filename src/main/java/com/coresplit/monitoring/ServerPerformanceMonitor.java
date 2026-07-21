package com.coresplit.monitoring;

import com.coresplit.CoreSplitMod;
import com.coresplit.config.CoreSplitConfig;

import java.util.concurrent.atomic.AtomicLong;

public class ServerPerformanceMonitor {

    private final CoreSplitConfig config;
    private final long[] recentNanos = new long[100];
    private int idx = 0;
    private long tickStart = 0;
    private final AtomicLong totalTicks = new AtomicLong(0);
    private float tps = 20.0f;
    private float mspt = 0.0f;

    public ServerPerformanceMonitor(CoreSplitConfig config) {
        this.config = config;
    }

    public void onTickStart() {
        tickStart = System.nanoTime();
    }

    public void onTickEnd() {
        long elapsed = System.nanoTime() - tickStart;
        synchronized (recentNanos) {
            recentNanos[idx % recentNanos.length] = elapsed;
            idx++;
        }
        totalTicks.incrementAndGet();
        if (totalTicks.get() % 20 == 0) {
            synchronized (recentNanos) {
                int count = Math.min(idx, recentNanos.length);
                if (count > 0) {
                    long sum = 0;
                    for (int i = 0; i < count; i++) sum += recentNanos[i];
                    double avg = (sum / (double) count) / 1_000_000.0;
                    mspt = (float) avg;
                    tps = (float) Math.min(20.0, 1000.0 / avg);
                }
            }
        }
    }

    public float getTPS()        { return tps; }
    public float getMSPT()       { return mspt; }
    public long  getTotalTicks() { return totalTicks.get(); }

    public void dumpSummary() {
        CoreSplitMod.LOGGER.info("=== CoreSplit Final === TPS=" + String.format("%.1f", tps)
                + " MSPT=" + String.format("%.1f", mspt) + "ms ticks=" + totalTicks.get());
    }
}