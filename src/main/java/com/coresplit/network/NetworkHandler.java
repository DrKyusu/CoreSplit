package com.coresplit.network;

import com.coresplit.monitoring.ServerPerformanceMonitor;

public class NetworkHandler {

    private static int counter = 0;
    private static volatile String snapshot = "";

    public static void updateSnapshot(ServerPerformanceMonitor m) {
        counter++;
        if (counter < 20) return;
        counter = 0;
        snapshot = String.format("%.2f|%.2f|%d", m.getTPS(), m.getMSPT(), m.getTotalTicks());
    }

    public static String getSnapshot() { return snapshot; }
}