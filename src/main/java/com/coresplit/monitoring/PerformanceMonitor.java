package com.coresplit.monitoring;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public class PerformanceMonitor {

    // PERF: 告警检查节流间隔（0.5s），避免每帧调用 checkAlerts 浪费 CPU
    private static final long ALERT_CHECK_INTERVAL_NS = 500_000_000L;

    public enum MetricType {
        FPS("FPS", "Frames per second", 0, 500),
        MSPT("MSPT", "Milliseconds per tick", 0, 100),
        CHUNK_LOAD_RATE("Chunk/s", "Chunks loaded per second", 0, 100),
        PENDING_CHUNKS("Pending", "Pending chunk tasks", 0, 1000),
        TEXTURE_CACHE_HIT("Tex Hit%", "Texture cache hit rate", 0, 100),
        TEXTURE_MEMORY("Tex Mem", "Texture memory usage (MB)", 0, 1024),
        DRAW_CALLS("Draw Calls", "GPU draw calls per frame", 0, 10000),
        CPU_USAGE("CPU%", "CPU usage percentage", 0, 100),
        MEMORY_USAGE("Memory MB", "Heap memory usage", 0, 8192),
        GC_COUNT("GC/s", "Garbage collections per second", 0, 100),
        ENTITY_COUNT("Entities", "Active entity count", 0, 10000),
        PARTICLE_COUNT("Particles", "Active particle count", 0, 100000),
        // 新增指标：用于高负载场景监控
        GPU_USAGE("GPU%", "GPU usage percentage", 0, 100),
        TNT_ACTIVE("TNT", "Active TNT entities", 0, 20000),
        AI_TICK_RATE("AI/s", "AI ticks per second", 0, 20),
        PATH_CACHE_HIT("Path Hit%", "Path cache hit rate", 0, 100),
        EXPLOSION_QUEUE("ExplQ", "Pending explosion queue size", 0, 20000),
        MEMORY_SAVED_MB("Mem Saved", "Memory saved by pooling/eviction (MB)", 0, 4096);

        public final String id;
        public final String description;
        public final float min;
        public final float max;

        MetricType(String id, String description, float min, float max) {
            this.id = id;
            this.description = description;
            this.min = min;
            this.max = max;
        }
    }

    public enum AlertLevel {
        NONE,
        WARNING,
        CRITICAL
    }

    public static class Metric {
        public final MetricType type;
        public volatile float value;
        public volatile float minValue = Float.MAX_VALUE;
        public volatile float maxValue = Float.MIN_VALUE;
        public volatile float avgValue;
        
        private final float[] history = new float[60];
        private volatile int historyIdx = 0;
        private volatile float historySum = 0;
        // 修复BUG: 原代码 count = historyIdx==0 ? length : historyIdx，环形缓冲填满回绕后
        // historyIdx∈[1,59] 时用部分计数除以 60 元素之和，导致 avgValue 严重偏大（60 帧中 59 帧错误）。
        // 引入总更新次数，count = min(totalUpdates, length) 始终正确。
        private long totalUpdates = 0;

        public Metric(MetricType type) {
            this.type = type;
        }

        public void update(float newValue) {
            // 修复BUG: 原代码非线程安全，多字段更新非原子，加synchronized保证一致性
            synchronized (this) {
                this.value = newValue;
                this.minValue = Math.min(minValue, newValue);
                this.maxValue = Math.max(maxValue, newValue);

                historySum -= history[historyIdx];
                history[historyIdx] = newValue;
                historySum += newValue;
                historyIdx = (historyIdx + 1) % history.length;
                totalUpdates++;

                // 修复BUG: 填满回绕后必须用总更新次数计算 count，否则 avg 严重失真
                int count = (int) Math.min(totalUpdates, history.length);
                this.avgValue = historySum / count;
            }
        }

        public synchronized float[] getHistory() {
            // 修复BUG: 原代码长度与顺序均错误——填满回绕后 historyIdx∈[1,59] 时返回长度 1..59（应 60），
            // 且直接 copy history[0..n] 未按写入时间重排（回绕后最旧数据在 history[historyIdx]）。
            int count = (int) Math.min(totalUpdates, history.length);
            float[] result = new float[count];
            if (totalUpdates <= history.length) {
                // 未填满：history[0..count-1] 即按时间顺序的样本
                System.arraycopy(history, 0, result, 0, count);
            } else {
                // 已填满回绕：最旧样本在 history[historyIdx]，从该处按写入顺序复制一圈
                int firstLen = history.length - historyIdx;
                System.arraycopy(history, historyIdx, result, 0, firstLen);
                System.arraycopy(history, 0, result, firstLen, historyIdx);
            }
            return result;
        }
    }

    public static class Alert {
        public final MetricType metric;
        public final AlertLevel level;
        public final float threshold;
        public final float currentValue;
        public final long timestamp;
        public final String message;

        public Alert(MetricType metric, AlertLevel level, float threshold, float currentValue, String message) {
            this.metric = metric;
            this.level = level;
            this.threshold = threshold;
            this.currentValue = currentValue;
            this.timestamp = System.currentTimeMillis();
            this.message = message;
        }
    }

    private final Map<MetricType, Metric> metrics = new ConcurrentHashMap<>();
    private final Map<String, Float> customMetrics = new ConcurrentHashMap<>();
    // PERF: ArrayDeque 比 LinkedList 每节点节省 24 字节内存，add/poll/peek 均为 O(1)
    private final Deque<Alert> alerts = new ArrayDeque<>();
    // 修复BUG: 原代码使用HashSet非线程安全，并发修改和迭代会ConcurrentModificationException，改用CopyOnWriteArraySet
    private final Set<MetricType> enabledMetrics = new CopyOnWriteArraySet<>();
    
    private final AtomicLong frameCount = new AtomicLong(0);
    private volatile long lastFrameTime = 0;
    private volatile float fps = 0;
    // PERF: 记录上次告警检查的时间戳，用于节流
    private volatile long lastAlertCheckNs = 0;

    private static PerformanceMonitor instance;

    public static synchronized PerformanceMonitor getInstance() {
        if (instance == null) {
            instance = new PerformanceMonitor();
        }
        return instance;
    }

    private PerformanceMonitor() {
        for (MetricType type : MetricType.values()) {
            metrics.put(type, new Metric(type));
            enabledMetrics.add(type);
        }
    }

    public void onFrameStart() {
        lastFrameTime = System.nanoTime();
    }

    public void onFrameEnd() {
        long now = System.nanoTime();
        long delta = now - lastFrameTime;

        if (delta > 0) {
            float frameTimeMs = delta / 1_000_000.0f;
            fps = 1000.0f / frameTimeMs;

            updateMetric(MetricType.FPS, fps);
            updateMetric(MetricType.MSPT, frameTimeMs);
        }

        frameCount.incrementAndGet();
        // PERF: 原代码每帧调用 checkAlerts（60+FPS），内含 Runtime.getRuntime() 和多次 map 查询；
        // 节流到每 0.5s 检查一次，告警延迟可接受
        if (now - lastAlertCheckNs >= ALERT_CHECK_INTERVAL_NS) {
            lastAlertCheckNs = now;
            checkAlerts();
        }
    }

    public void updateMetric(MetricType type, float value) {
        Metric metric = metrics.get(type);
        if (metric != null) {
            metric.update(value);
        }
    }

    public void updateCustomMetric(String name, float value) {
        customMetrics.put(name, value);
    }

    public float getMetricValue(MetricType type) {
        Metric metric = metrics.get(type);
        return metric != null ? metric.value : 0;
    }

    public Metric getMetric(MetricType type) {
        return metrics.get(type);
    }

    public float getCustomMetric(String name) {
        return customMetrics.getOrDefault(name, 0f);
    }

    public Collection<Metric> getEnabledMetrics() {
        return enabledMetrics.stream()
                .map(metrics::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public Set<MetricType> getEnabledMetricTypes() {
        return Collections.unmodifiableSet(enabledMetrics);
    }

    public void setMetricEnabled(MetricType type, boolean enabled) {
        if (enabled) {
            enabledMetrics.add(type);
        } else {
            enabledMetrics.remove(type);
        }
    }

    private void checkAlerts() {
        List<Alert> newAlerts = new ArrayList<>();

        // PERF: 每个 metric 仅查询一次存局部变量，避免重复 ConcurrentHashMap.get 调用
        float fpsValue = getMetricValue(MetricType.FPS);
        if (fpsValue < 20) {
            newAlerts.add(new Alert(MetricType.FPS, AlertLevel.CRITICAL, 20, fpsValue,
                    "FPS dropped below critical threshold"));
        } else if (fpsValue < 30) {
            newAlerts.add(new Alert(MetricType.FPS, AlertLevel.WARNING, 30, fpsValue,
                    "FPS dropped below warning threshold"));
        }

        float msptValue = getMetricValue(MetricType.MSPT);
        if (msptValue > 80) {
            newAlerts.add(new Alert(MetricType.MSPT, AlertLevel.CRITICAL, 80, msptValue,
                    "MSPT exceeded critical threshold"));
        } else if (msptValue > 50) {
            newAlerts.add(new Alert(MetricType.MSPT, AlertLevel.WARNING, 50, msptValue,
                    "MSPT exceeded warning threshold"));
        }

        float cpuValue = getMetricValue(MetricType.CPU_USAGE);
        if (cpuValue > 90) {
            newAlerts.add(new Alert(MetricType.CPU_USAGE, AlertLevel.CRITICAL, 90, cpuValue,
                    "CPU usage exceeded critical threshold"));
        }

        // PERF: Runtime.getRuntime() 仅调用一次，结果复用
        Runtime runtime = Runtime.getRuntime();
        float memoryUsage = (runtime.totalMemory() - runtime.freeMemory()) / (1024f * 1024f);
        float maxMemory = runtime.maxMemory() / (1024f * 1024f);
        float memoryPercent = (memoryUsage / maxMemory) * 100;

        if (memoryPercent > 95) {
            newAlerts.add(new Alert(MetricType.MEMORY_USAGE, AlertLevel.CRITICAL, 95, memoryPercent,
                    "Memory usage exceeded critical threshold"));
        } else if (memoryPercent > 80) {
            newAlerts.add(new Alert(MetricType.MEMORY_USAGE, AlertLevel.WARNING, 80, memoryPercent,
                    "Memory usage exceeded warning threshold"));
        }

        synchronized (alerts) {
            for (Alert alert : newAlerts) {
                alerts.add(alert);
                if (alerts.size() > 100) {
                    alerts.poll();
                }
                
                CoreSplitMod.LOGGER.warn("[CoreSplit Alert] {} {}: {} - {}", 
                        alert.level, alert.metric.id, alert.message, alert.currentValue);
            }
        }
    }

    public List<Alert> getRecentAlerts(int limit) {
        synchronized (alerts) {
            List<Alert> result = new ArrayList<>(alerts);
            Collections.reverse(result);
            return result.stream().limit(limit).toList();
        }
    }

    public List<Alert> getAlertsByLevel(AlertLevel level) {
        synchronized (alerts) {
            return alerts.stream()
                    .filter(a -> a.level == level)
                    .toList();
        }
    }

    public void clearAlerts() {
        synchronized (alerts) {
            alerts.clear();
        }
    }

    public long getFrameCount() {
        return frameCount.get();
    }

    public float getFPS() {
        return fps;
    }

    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CoreSplit Performance Report ===\n");
        sb.append("Time: ").append(new Date()).append("\n");
        sb.append("Frame Count: ").append(frameCount.get()).append("\n\n");
        
        for (MetricType type : MetricType.values()) {
            Metric metric = metrics.get(type);
            if (metric != null && enabledMetrics.contains(type)) {
                sb.append(String.format("  %-15s %.1f (avg: %.1f, min: %.1f, max: %.1f)\n",
                        type.id + ":", metric.value, metric.avgValue, metric.minValue, metric.maxValue));
            }
        }
        
        if (!customMetrics.isEmpty()) {
            sb.append("\nCustom Metrics:\n");
            for (Map.Entry<String, Float> entry : customMetrics.entrySet()) {
                sb.append(String.format("  %-15s %.2f\n", entry.getKey() + ":", entry.getValue()));
            }
        }
        
        synchronized (alerts) {
            if (!alerts.isEmpty()) {
                sb.append("\nActive Alerts (").append(alerts.size()).append("):\n");
                for (Alert alert : alerts.stream().limit(5).toList()) {
                    sb.append(String.format("  [%s] %s: %s\n", 
                            alert.level, alert.metric.id, alert.message));
                }
            }
        }
        
        return sb.toString();
    }

    public void dumpReport() {
        CoreSplitMod.LOGGER.info(generateReport());
    }

    public void reset() {
        frameCount.set(0);
        fps = 0;
        lastAlertCheckNs = 0;
        customMetrics.clear();
        clearAlerts();
        
        for (Metric metric : metrics.values()) {
            metric.value = 0;
            metric.minValue = Float.MAX_VALUE;
            metric.maxValue = Float.MIN_VALUE;
            metric.avgValue = 0;
            metric.historyIdx = 0;
            metric.historySum = 0;
            Arrays.fill(metric.history, 0);
        }
    }
}