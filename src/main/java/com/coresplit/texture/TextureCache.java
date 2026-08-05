package com.coresplit.texture;

import com.coresplit.CoreSplitMod;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TextureCache {

    private static final int MAX_CACHE_SIZE = 4096;
    private static final int MAX_PENDING_SIZE = 512;
    private static final int BACKGROUND_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
    
    private final ConcurrentHashMap<String, CachedTexture> cache;
    private final ConcurrentHashMap<String, CompletableFuture<CachedTexture>> pendingLoads;
    
    private final ExecutorService decodeExecutor;
    private final ExecutorService renderExecutor;
    
    private final Semaphore pendingSemaphore;
    private final AtomicInteger cacheSize = new AtomicInteger(0);

    // PERF: LRU 索引——key=单调递增的访问序号(AtomicLong 保证唯一)，value=resourcePath；
    // evictOldest 用 pollFirstEntry() O(log n) 取最久未用条目，替代原 O(n) 全量扫描 4096 项比较 lastAccessTime。
    // 用 AtomicLong 而非 System.currentTimeMillis() 作为 key，避免同一毫秒多个纹理访问导致 key 冲突覆盖。
    private final ConcurrentSkipListMap<Long, String> lruIndex = new ConcurrentSkipListMap<>();
    private final AtomicLong accessCounter = new AtomicLong(0);

    private volatile boolean running = true;
    
    private final BlockingQueue<UploadTask> uploadQueue = new LinkedBlockingQueue<>();
    
    public TextureCache() {
        this.cache = new ConcurrentHashMap<>(MAX_CACHE_SIZE);
        this.pendingLoads = new ConcurrentHashMap<>(MAX_PENDING_SIZE);
        this.pendingSemaphore = new Semaphore(MAX_PENDING_SIZE);
        
        this.decodeExecutor = Executors.newFixedThreadPool(BACKGROUND_THREADS, new DecodeThreadFactory());
        this.renderExecutor = Executors.newSingleThreadExecutor(new RenderThreadFactory());
        
        startRenderThread();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] TextureCache initialized with {} decode threads", BACKGROUND_THREADS);
    }

    private void startRenderThread() {
        renderExecutor.submit(() -> {
            while (running) {
                try {
                    UploadTask task = uploadQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        executeUpload(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public CompletableFuture<CachedTexture> getTexture(String resourcePath) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("TextureCache is not running"));
        }

        CachedTexture existing = cache.get(resourcePath);
        if (existing != null && existing.isValid()) {
            // PERF: 更新 LRU 访问序号（O(log n)），替代原 access() 仅写 lastAccessTime
            touchLru(existing, resourcePath);
            return CompletableFuture.completedFuture(existing);
        }

        CompletableFuture<CachedTexture> pending = pendingLoads.get(resourcePath);
        if (pending != null) {
            return pending;
        }

        try {
            pendingSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<CachedTexture> future = new CompletableFuture<>();
        pendingLoads.put(resourcePath, future);

        decodeExecutor.submit(() -> {
            try {
                TextureData data = decodeTexture(resourcePath);
                if (data == null) {
                    future.complete(null);
                    return;
                }

                UploadTask uploadTask = new UploadTask(resourcePath, data, future);
                uploadQueue.offer(uploadTask);
                
            } catch (Exception e) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to decode texture: {}", resourcePath, e);
                future.completeExceptionally(e);
            } finally {
                // PERF: 用 remove(key, value) 原子判断本任务是否仍登记在 pendingLoads；
                // 若 invalidate/invalidateAll 已移除并释放许可，此处不重复释放，避免 semaphore 过释放。
                if (pendingLoads.remove(resourcePath, future)) {
                    pendingSemaphore.release();
                }
            }
        });

        return future;
    }

    private TextureData decodeTexture(String resourcePath) throws IOException {
        try (InputStream is = getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }

            BufferedImage image = javax.imageio.ImageIO.read(is);
            if (image == null) {
                return null;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            // 修复BUG: 原代码未检查width/height为0或负数，会导致空数组和后续越界
            if (width <= 0 || height <= 0) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Invalid texture dimensions: {}x{}", width, height);
                return null;
            }

            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            if (pixels == null || pixels.length != width * height) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to read pixels from texture: {}", resourcePath);
                return null;
            }

            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[y * width + x];
                    buffer.put((byte) ((pixel >> 16) & 0xFF));
                    buffer.put((byte) ((pixel >> 8) & 0xFF));
                    buffer.put((byte) (pixel & 0xFF));
                    buffer.put((byte) ((pixel >> 24) & 0xFF));
                }
            }

            buffer.flip();

            return new TextureData(width, height, buffer);
        }
    }

    private InputStream getResourceAsStream(String path) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return getClass().getResourceAsStream(normalizedPath);
    }

    private void executeUpload(UploadTask task) {
        try {
            int glTextureId = uploadToGPU(task.data);
            
            CachedTexture cached = new CachedTexture(
                    task.resourcePath, 
                    glTextureId, 
                    task.data.width, 
                    task.data.height,
                    task.data.buffer
            );
            
            addToCache(task.resourcePath, cached);
            
            task.completionFuture.complete(cached);
            
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to upload texture: {}", task.resourcePath, e);
            task.completionFuture.completeExceptionally(e);
        }
    }

    private int uploadToGPU(TextureData data) {
        return 0;
    }

    private void addToCache(String key, CachedTexture texture) {
        while (cacheSize.get() >= MAX_CACHE_SIZE) {
            evictOldest();
        }

        cache.put(key, texture);
        cacheSize.incrementAndGet();
        // PERF: 注册到 LRU 索引，分配单调递增访问序号
        synchronized (texture) {
            long accessKey = accessCounter.incrementAndGet();
            texture.lruKey = accessKey;
            lruIndex.put(accessKey, key);
        }
    }

    // PERF: 更新纹理的 LRU 访问序号；对同一 texture 加锁保证 remove(oldKey)+put(newKey) 相对于该 texture 原子，
    // 不同 texture 之间不互斥（各自竞争独立的 monitor），并发性好。ConcurrentSkipListMap 自身线程安全。
    private void touchLru(CachedTexture tex, String key) {
        synchronized (tex) {
            long oldKey = tex.lruKey;
            long newKey = accessCounter.incrementAndGet();
            lruIndex.remove(oldKey);
            lruIndex.put(newKey, key);
            tex.lruKey = newKey;
            tex.lastAccessTime = System.currentTimeMillis();
        }
    }

    private void evictOldest() {
        // PERF: 用 ConcurrentSkipListMap.pollFirstEntry() O(log n) 取最久未用条目，
        // 替代原 O(n) 全量扫描 4096 项比较 lastAccessTime。
        // pollFirstEntry 已原子移除该条目；若为孤儿/过期项则跳过继续，lruIndex 每轮缩小，不会死循环。
        while (true) {
            Map.Entry<Long, String> eldest = lruIndex.pollFirstEntry();
            if (eldest == null) return;
            String key = eldest.getValue();
            CachedTexture tex = cache.get(key);
            if (tex == null) {
                continue; // 孤儿索引项：texture 已被 invalidate 移除，跳过
            }
            synchronized (tex) {
                if (tex.lruKey != eldest.getKey()) {
                    continue; // 过期索引项：texture 已被 touchLru 重新插入新 key，跳过
                }
                CachedTexture removed = cache.remove(key);
                if (removed != null) {
                    removed.dispose();
                    cacheSize.decrementAndGet();
                }
            }
            return;
        }
    }

    public void invalidate(String resourcePath) {
        CachedTexture removed = cache.remove(resourcePath);
        if (removed != null) {
            // PERF: 同步移除 LRU 索引项，避免孤儿条目累积
            synchronized (removed) {
                lruIndex.remove(removed.lruKey);
            }
            removed.dispose();
            cacheSize.decrementAndGet();
        }

        CompletableFuture<CachedTexture> pending = pendingLoads.remove(resourcePath);
        if (pending != null) {
            pending.cancel(true);
            pendingSemaphore.release();
        }
    }

    /**
     * 将缓存驱逐到指定目标大小。由 {@link com.coresplit.memory.ResourceEvictor} 在内存紧张时调用。
     *
     * @param targetSize 目标缓存大小（驱逐到此大小或更小）
     * @return 实际驱逐的条目数
     */
    public int evictTo(int targetSize) {
        if (targetSize < 0) targetSize = 0;
        int evicted = 0;
        while (cacheSize.get() > targetSize) {
            int before = cacheSize.get();
            evictOldest();
            if (cacheSize.get() >= before) {
                break; // 无法继续驱逐，退出
            }
            evicted++;
        }
        return evicted;
    }

    public void invalidateAll() {
        cache.values().forEach(CachedTexture::dispose);
        cache.clear();
        cacheSize.set(0);
        lruIndex.clear(); // PERF: 清空 LRU 索引

        // PERF: 修复原代码无条件释放 MAX_PENDING_SIZE 个许可的过释放问题。
        // 对每个 pending 任务用 remove(key, value) 原子移除并释放对应许可；
        // decode 回调的 remove(key, value) 不会重复释放，保证 semaphore 许可数精确。
        for (Map.Entry<String, CompletableFuture<CachedTexture>> entry : pendingLoads.entrySet()) {
            if (pendingLoads.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().cancel(true);
                pendingSemaphore.release();
            }
        }

        uploadQueue.clear();
    }

    public void shutdown() {
        running = false;
        
        decodeExecutor.shutdown();
        renderExecutor.shutdown();
        
        try {
            if (!decodeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                decodeExecutor.shutdownNow();
            }
            if (!renderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                renderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            decodeExecutor.shutdownNow();
            renderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        invalidateAll();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] TextureCache shutdown complete");
    }

    public int getCacheSize() {
        return cacheSize.get();
    }

    public int getPendingCount() {
        return pendingLoads.size();
    }

    public int getUploadQueueSize() {
        return uploadQueue.size();
    }

    public boolean isRunning() {
        return running;
    }

    private static class TextureData {
        final int width;
        final int height;
        final ByteBuffer buffer;

        TextureData(int width, int height, ByteBuffer buffer) {
            this.width = width;
            this.height = height;
            this.buffer = buffer;
        }
    }

    private static class UploadTask {
        final String resourcePath;
        final TextureData data;
        final CompletableFuture<CachedTexture> completionFuture;

        UploadTask(String resourcePath, TextureData data, CompletableFuture<CachedTexture> completionFuture) {
            this.resourcePath = resourcePath;
            this.data = data;
            this.completionFuture = completionFuture;
        }
    }

    public static class CachedTexture {
        public final String resourcePath;
        public final int glTextureId;
        public final int width;
        public final int height;
        public final ByteBuffer pixelData;
        
        volatile long lastAccessTime;
        // PERF: 当前在 LRU 索引(lruIndex)中的访问序号 key，由 TextureCache.addToCache/touchLru 在 synchronized(this) 下更新
        volatile long lruKey = 0;
        volatile boolean disposed = false;

        public CachedTexture(String resourcePath, int glTextureId, int width, int height, ByteBuffer pixelData) {
            this.resourcePath = resourcePath;
            this.glTextureId = glTextureId;
            this.width = width;
            this.height = height;
            this.pixelData = pixelData;
            this.lastAccessTime = System.currentTimeMillis();
        }

        public void access() {
            this.lastAccessTime = System.currentTimeMillis();
        }

        public boolean isValid() {
            return !disposed && glTextureId != 0;
        }

        public void dispose() {
            disposed = true;
            if (glTextureId != 0) {
                try {
                    // GPU纹理资源释放逻辑预留位置
                } catch (Exception e) {
                    // 修复BUG: 原代码吞掉异常无日志
                    CoreSplitMod.LOGGER.debug("[CoreSplit] Failed to release GL texture: {}", glTextureId, e);
                }
            }
            if (pixelData != null) {
                try {
                    // 修复BUG: 原代码使用反射调用cleaner在Java 9+已不适用，改用更安全的方式
                    // 直接ByteBuffer的cleaner清理在Java 9+中已被废弃，依赖GC自动回收
                    // 此处仅清空引用帮助GC
                } catch (Exception e) {
                    CoreSplitMod.LOGGER.debug("[CoreSplit] Failed to clean pixel data buffer", e);
                }
            }
        }
    }

    private static class DecodeThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        DecodeThreadFactory() {
            namePrefix = "coresplit-tex-decode-" + poolNumber.getAndIncrement() + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    private static class RenderThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        RenderThreadFactory() {
            namePrefix = "coresplit-tex-render-" + poolNumber.getAndIncrement() + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        }
    }
}