package com.coresplit.model;

import com.coresplit.CoreSplitMod;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class CemModelLoader {

    private static final int MAX_CACHE_SIZE = 1024;
    private static final int MIN_BACKGROUND_THREADS = 2;
    private static final int BACKGROUND_THREADS = Math.max(MIN_BACKGROUND_THREADS,
            Runtime.getRuntime().availableProcessors() / 4);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final String JEM_EXTENSION = ".jem";
    private static final String ENTITY_PATH_PREFIX = "/entity/";
    private static final String MODELS_ENTITY_PATH_PREFIX = "/models/entity/";
    private static final int MODELS_ENTITY_PREFIX_LENGTH = MODELS_ENTITY_PATH_PREFIX.length();

    // 修复BUG: 原线程池使用 Executors.newFixedThreadPool，内部队列无界，
    // 压力测试下大量模型加载任务堆积会导致 OOM；改用有界队列 + CallerRunsPolicy 饱和策略，
    // 队列满时由提交线程自行执行，天然限流且不会丢失任务。
    private static final int MAX_PENDING_TASKS = 512;

    // 修复BUG: 原警告日志无限流，压力测试下大量模型加载失败会刷屏日志；
    // 采用令牌桶限流（每 10 秒最多 10 条警告），避免日志风暴。
    private static final long WARN_RATE_LIMIT_MS = 10_000L;
    private static final int WARN_MAX_PER_WINDOW = 10;

    // PERF: 原 synchronizedMap + LinkedHashMap 锁粒度大，高并发读场景下竞争激烈；
    // 改用 ConcurrentHashMap 存储模型 + ConcurrentSkipListMap 维护 LRU 访问顺序（O(log n) 驱逐），
    // 读操作完全无锁，写操作只锁单个条目，吞吐显著提升。
    private final ConcurrentHashMap<String, JemFileParser.CemModel> modelCache;
    private final ConcurrentSkipListMap<Long, String> lruOrder;
    private final AtomicLong lruCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> lruKeyMap;
    private final ConcurrentHashMap<String, CompletableFuture<JemFileParser.CemModel>> pendingLoads;
    private final ExecutorService loadExecutor;

    private final AtomicLong lastWarnTime = new AtomicLong(0);
    private final AtomicLong warnCount = new AtomicLong(0);

    private volatile boolean running = true;

    public CemModelLoader() {
        this.modelCache = new ConcurrentHashMap<>(MAX_CACHE_SIZE);
        this.lruOrder = new ConcurrentSkipListMap<>();
        this.lruKeyMap = new ConcurrentHashMap<>(MAX_CACHE_SIZE);
        this.pendingLoads = new ConcurrentHashMap<>(128);
        // 有界队列 + CallerRunsPolicy：队列满时提交线程自己执行，天然限流
        this.loadExecutor = new ThreadPoolExecutor(
                BACKGROUND_THREADS, BACKGROUND_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(MAX_PENDING_TASKS),
                r -> {
                    Thread t = new Thread(r, "coresplit-model-loader-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        CoreSplitMod.LOGGER.info("[CoreSplit] CemModelLoader initialized with {} threads", BACKGROUND_THREADS);
    }

    // 警告限流
    private boolean allowWarn() {
        long now = System.currentTimeMillis();
        long last = lastWarnTime.get();
        if (now - last > WARN_RATE_LIMIT_MS) {
            lastWarnTime.set(now);
            warnCount.set(1);
            return true;
        }
        return warnCount.incrementAndGet() <= WARN_MAX_PER_WINDOW;
    }

    // 访问时更新 LRU 顺序
    private void touchLru(String key) {
        Long oldLruKey = lruKeyMap.get(key);
        if (oldLruKey != null) {
            lruOrder.remove(oldLruKey);
        }
        long newLruKey = lruCounter.incrementAndGet();
        lruOrder.put(newLruKey, key);
        lruKeyMap.put(key, newLruKey);
    }

    // LRU 驱逐：超过 MAX_CACHE_SIZE 时移除最久未访问的条目
    private void evictIfNeeded() {
        while (modelCache.size() > MAX_CACHE_SIZE) {
            Map.Entry<Long, String> eldest = lruOrder.firstEntry();
            if (eldest == null) break;
            String evictedKey = eldest.getValue();
            lruOrder.remove(eldest.getKey());
            lruKeyMap.remove(evictedKey);
            modelCache.remove(evictedKey);
        }
    }

    public CompletableFuture<JemFileParser.CemModel> loadModel(String resourcePath) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("CemModelLoader is not running"));
        }

        JemFileParser.CemModel existing = modelCache.get(resourcePath);
        if (existing != null) {
            touchLru(resourcePath);
            return CompletableFuture.completedFuture(existing);
        }

        // 修复BUG: 原代码先get再put存在竞态，多线程同时加载同一资源会重复提交任务；改用putIfAbsent保证原子性
        CompletableFuture<JemFileParser.CemModel> future = new CompletableFuture<>();
        CompletableFuture<JemFileParser.CemModel> race = pendingLoads.putIfAbsent(resourcePath, future);
        if (race != null) {
            return race;
        }

        try {
            loadExecutor.submit(() -> {
                try {
                    JemFileParser.CemModel model = loadModelFromResource(resourcePath);
                    if (model != null) {
                        modelCache.put(resourcePath, model);
                        touchLru(resourcePath);
                        evictIfNeeded();
                    }
                    future.complete(model);
                } catch (Exception e) {
                    if (allowWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load model: {}", resourcePath, e);
                    }
                    future.completeExceptionally(e);
                } finally {
                    pendingLoads.remove(resourcePath);
                }
            });
        } catch (Exception e) {
            // 修复BUG: 若线程池已关闭导致submit抛RejectedExecutionException，原代码返回的future永远不会完成，调用方将永久阻塞
            future.completeExceptionally(e);
            pendingLoads.remove(resourcePath);
        }

        return future;
    }

    private JemFileParser.CemModel loadModelFromResource(String resourcePath) {
        String normalizedPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        if (!normalizedPath.endsWith(JEM_EXTENSION)) {
            normalizedPath += JEM_EXTENSION;
        }

        try (InputStream is = getClass().getResourceAsStream(normalizedPath)) {
            if (is == null) {
                return null;
            }
            return JemFileParser.parse(is);
        } catch (Exception e) {
            if (allowWarn()) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Error reading model file: {}", resourcePath, e);
            }
            return null;
        }
    }

    public void loadResourcePackModels(Supplier<Map<String, InputStream>> resourceProvider) {
        Map<String, InputStream> resources = resourceProvider.get();

        for (Map.Entry<String, InputStream> entry : resources.entrySet()) {
            String path = entry.getKey();

            if (path.endsWith(JEM_EXTENSION)) {
                // 修复BUG: 原代码未在try-with-resources中关闭InputStream，若JemFileParser.parse在关闭流前抛异常会导致资源泄漏
                try (InputStream is = entry.getValue()) {
                    JemFileParser.CemModel model = JemFileParser.parse(is);
                    if (model != null) {
                        String entityId = extractEntityIdFromPath(path);
                        if (!entityId.isEmpty()) {
                            modelCache.put(entityId, model);
                            touchLru(entityId);
                            evictIfNeeded();
                            CoreSplitMod.LOGGER.debug("[CoreSplit] Loaded CEM model for {}: {}", entityId, path);
                        }
                    }
                } catch (Exception e) {
                    if (allowWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to load CEM model: {}", path, e);
                    }
                }
            }
        }
    }

    private String extractEntityIdFromPath(String path) {
        if (path.contains(ENTITY_PATH_PREFIX)) {
            int start = path.indexOf(ENTITY_PATH_PREFIX) + ENTITY_PATH_PREFIX.length();
            int end = path.lastIndexOf('.');
            if (start < end) {
                return path.substring(start, end);
            }
        } else if (path.contains(MODELS_ENTITY_PATH_PREFIX)) {
            int start = path.indexOf(MODELS_ENTITY_PATH_PREFIX) + MODELS_ENTITY_PREFIX_LENGTH;
            int end = path.lastIndexOf('.');
            if (start < end) {
                return path.substring(start, end);
            }
        }
        return "";
    }

    public JemFileParser.CemModel getModel(String entityId) {
        JemFileParser.CemModel model = modelCache.get(entityId);
        if (model != null) {
            touchLru(entityId);
        }
        return model;
    }

    public void invalidate(String entityId) {
        modelCache.remove(entityId);
        Long lruKey = lruKeyMap.remove(entityId);
        if (lruKey != null) {
            lruOrder.remove(lruKey);
        }
    }

    public void invalidateAll() {
        modelCache.clear();
        lruOrder.clear();
        lruKeyMap.clear();
        pendingLoads.values().forEach(f -> f.cancel(true));
        pendingLoads.clear();
    }

    public void shutdown() {
        running = false;
        loadExecutor.shutdown();

        try {
            if (!loadExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                loadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            loadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        invalidateAll();
        CoreSplitMod.LOGGER.info("[CoreSplit] CemModelLoader shutdown complete");
    }

    public int getCachedModelCount() {
        return modelCache.size();
    }

    public boolean isRunning() {
        return running;
    }
}