package com.coresplit.monitoring;

public class ClientMetricsCache {

    private static volatile MetricsSnapshot latest = MetricsSnapshot.EMPTY;

    public static void update(MetricsSnapshot s) { latest = s; }
    public static MetricsSnapshot getLatest() { return latest; }

    public record MetricsSnapshot(float tps, float mspt, long totalTicks) {
        public static final MetricsSnapshot EMPTY = new MetricsSnapshot(0, 0, 0);
        public boolean hasData() { return totalTicks > 0; }
        public static MetricsSnapshot from(String data) {
            try {
                String[] p = data.split("\\|");
                return new MetricsSnapshot(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Long.parseLong(p[2]));
            } catch (Exception e) { return EMPTY; }
        }
    }
}