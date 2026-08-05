package com.coresplit.chunk;

import com.coresplit.CoreSplitMod;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncChunkIO {

    private static final int MAX_PENDING_OPS = 128;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;
    // PERF: 写缓冲复用池上限。借用/归还模型保证每个在途写入独占 buffer，避免 ThreadLocal 在异步消费场景下的复写风险。
    private static final int MAX_BUFFER_POOL_SIZE = 32;

    private final ExecutorService ioExecutor;
    private final ExecutorService completionExecutor;
    
    private final Semaphore pendingOpsSemaphore;
    private final ConcurrentHashMap<Long, CompletableFuture<ByteBuffer>> pendingReads;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingWrites;
    
    private volatile boolean running = true;
    private final AtomicInteger activeOps = new AtomicInteger(0);

    // PERF: ByteBuffer 复用池，替代 writeChunkAsync 中每次 ByteBuffer.allocate 的小对象分配与 GC 压力。
    // 不使用 ThreadLocal：copy 在调用方线程填充、在 io 线程异步消费(writeResult.get())，
    // 同一调用线程连续提交两次写入时，前一次的 io 写入可能尚未完成，ThreadLocal 会复写仍被 io 线程读取的 buffer。
    // 池化“借用(acquire)→填充→异步写入→归还(release)”保证每个在途写入独占 buffer，安全且复用。
    private final ConcurrentLinkedQueue<ByteBuffer> bufferPool = new ConcurrentLinkedQueue<>();

    public AsyncChunkIO(int threadCount) {
        threadCount = Math.max(1, threadCount);
        
        this.ioExecutor = Executors.newFixedThreadPool(threadCount, new IOThreadFactory());
        this.completionExecutor = Executors.newSingleThreadExecutor(new CompletionThreadFactory());
        
        this.pendingOpsSemaphore = new Semaphore(MAX_PENDING_OPS);
        this.pendingReads = new ConcurrentHashMap<>();
        this.pendingWrites = new ConcurrentHashMap<>();
        
        CoreSplitMod.LOGGER.info("[CoreSplit] AsyncChunkIO initialized with {} threads", threadCount);
    }

    public CompletableFuture<ByteBuffer> readChunkAsync(String worldDir, int x, int z) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("AsyncChunkIO is not running"));
        }
        // 修复BUG: worldDir 未做 null 检查，Paths.get 内部会抛出 NPE
        if (worldDir == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("worldDir must not be null"));
        }

        long chunkKey = getChunkKey(x, z);

        // 修复BUG: pendingReads 的 get 与 put 非原子操作，并发调用同一 chunkKey 时两个线程
        // 可能都看到 existing 为 null/done，各自创建 future 并 put，造成重复 IO 与语义不一致，
        // 改用 ConcurrentHashMap.compute 原子地检查并设置
        final CompletableFuture<ByteBuffer> newFuture = new CompletableFuture<>();
        CompletableFuture<ByteBuffer> future = pendingReads.compute(chunkKey,
                (k, existing) -> (existing != null && !existing.isDone()) ? existing : newFuture);
        if (future != newFuture) {
            return future;
        }

        try {
            pendingOpsSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 修复BUG: 仅移除自己的 future，避免误删并发线程刚放入的 future
            pendingReads.remove(chunkKey, future);
            return CompletableFuture.failedFuture(e);
        }

        activeOps.incrementAndGet();

        // 修复BUG: ioExecutor 在 shutdown 后 submit 会抛 RejectedExecutionException，
        // 此时需释放已获取的 semaphore 与 activeOps，否则资源泄漏
        try {
            ioExecutor.submit(() -> {
                try {
                    ByteBuffer result = readChunkInternal(worldDir, x, z);
                    // 修复BUG: completionExecutor 在 shutdown 后 submit 会抛 RejectedExecutionException，
                    // 若不处理则 future 永不完成、semaphore 与 activeOps 永不释放，造成资源泄漏
                    completeReadFuture(future, chunkKey, result, null);
                } catch (Exception e) {
                    completeReadFuture(future, chunkKey, null, e);
                }
            });
        } catch (RejectedExecutionException ree) {
            completeReadFuture(future, chunkKey, null, ree);
        }

        return future;
    }

    // 修复BUG: 提取完成逻辑统一处理 RejectedExecutionException，避免 shutdown 时 future 悬空
    private void completeReadFuture(CompletableFuture<ByteBuffer> future, long chunkKey,
                                    ByteBuffer result, Throwable error) {
        Runnable completion = () -> {
            if (error != null) {
                future.completeExceptionally(error);
            } else {
                future.complete(result);
            }
            pendingReads.remove(chunkKey);
            pendingOpsSemaphore.release();
            activeOps.decrementAndGet();
        };
        try {
            completionExecutor.submit(completion);
        } catch (RejectedExecutionException ree) {
            completion.run();
        }
    }

    // 修复BUG: 提取完成逻辑统一处理 RejectedExecutionException，避免 shutdown 时 future 悬空
    private void completeWriteFuture(CompletableFuture<Void> future, long chunkKey, Throwable error) {
        Runnable completion = () -> {
            if (error != null) {
                future.completeExceptionally(error);
            } else {
                future.complete(null);
            }
            pendingWrites.remove(chunkKey);
            pendingOpsSemaphore.release();
            activeOps.decrementAndGet();
        };
        try {
            completionExecutor.submit(completion);
        } catch (RejectedExecutionException ree) {
            completion.run();
        }
    }

    public CompletableFuture<Void> writeChunkAsync(String worldDir, int x, int z, ByteBuffer data) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("AsyncChunkIO is not running"));
        }
        // 修复BUG: worldDir 与 data 未做 null 检查，data 为 null 时 data.remaining() 抛 NPE
        if (worldDir == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("worldDir must not be null"));
        }
        if (data == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("data must not be null"));
        }

        long chunkKey = getChunkKey(x, z);

        // 修复BUG: pendingWrites 的 get 与 put 非原子操作，并发调用同一 chunkKey 时会创建重复写入
        // 改用 ConcurrentHashMap.compute 原子地检查并设置
        final CompletableFuture<Void> newFuture = new CompletableFuture<>();
        CompletableFuture<Void> future = pendingWrites.compute(chunkKey,
                (k, existing) -> (existing != null && !existing.isDone()) ? existing : newFuture);
        if (future != newFuture) {
            return future;
        }

        try {
            pendingOpsSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 修复BUG: 仅移除自己的 future，避免误删并发线程刚放入的 future
            pendingWrites.remove(chunkKey, future);
            return CompletableFuture.failedFuture(e);
        }

        activeOps.incrementAndGet();

        // PERF: 从缓冲池借用复用 buffer，替代每次 ByteBuffer.allocate 分配；
        // 保留 data.mark()/reset() 保护调用方 buffer 的 position 不被修改
        ByteBuffer copy = acquireWriteBuffer(data.remaining());
        data.mark();
        copy.put(data);
        data.reset();
        copy.flip();

        // 修复BUG: ioExecutor 在 shutdown 后 submit 会抛 RejectedExecutionException，
        // 此时需释放已获取的 semaphore 与 activeOps，否则资源泄漏
        try {
            ioExecutor.submit(() -> {
                try {
                    writeChunkInternal(worldDir, x, z, copy);
                    // 修复BUG: completionExecutor 在 shutdown 后 submit 会抛 RejectedExecutionException，
                    // 不处理则 future 永不完成、semaphore 与 activeOps 永不释放
                    completeWriteFuture(future, chunkKey, null);
                } catch (Exception e) {
                    completeWriteFuture(future, chunkKey, e);
                } finally {
                    // PERF: io 线程写入完成后归还 buffer 到池，供后续写入复用
                    releaseWriteBuffer(copy);
                }
            });
        } catch (RejectedExecutionException ree) {
            // PERF: 任务被拒绝未执行，直接归还借用的 buffer
            releaseWriteBuffer(copy);
            completeWriteFuture(future, chunkKey, ree);
        }

        return future;
    }

    // PERF: 借用写缓冲。优先从池中取容量足够的 buffer 并 clear；池空或容量不足时分配新 buffer。
    private ByteBuffer acquireWriteBuffer(int requiredSize) {
        ByteBuffer buf = bufferPool.poll();
        if (buf == null || buf.capacity() < requiredSize) {
            return ByteBuffer.allocate(requiredSize);
        }
        buf.clear();
        return buf;
    }

    // PERF: 归还写缓冲。池未满则 offer 回池供复用；池满则丢弃交由 GC 回收，避免无界内存占用。
    private void releaseWriteBuffer(ByteBuffer buf) {
        if (buf == null) return;
        if (bufferPool.size() < MAX_BUFFER_POOL_SIZE) {
            buf.clear();
            bufferPool.offer(buf);
        }
    }

    private ByteBuffer readChunkInternal(String worldDir, int x, int z) throws Exception {
        Path filePath = getChunkPath(worldDir, x, z);
        
        if (!java.nio.file.Files.exists(filePath)) {
            return null;
        }

        try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                filePath, StandardOpenOption.READ)) {
            
            long fileSize = channel.size();
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(fileSize, BUFFER_SIZE));
            
            Future<Integer> readResult = channel.read(buffer, 0);
            int bytesRead = readResult.get();
            
            if (bytesRead > 0) {
                buffer.flip();
                return buffer;
            }
            
            return null;
        }
    }

    private void writeChunkInternal(String worldDir, int x, int z, ByteBuffer data) throws Exception {
        Path filePath = getChunkPath(worldDir, x, z);
        
        java.nio.file.Files.createDirectories(filePath.getParent());
        
        try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                filePath, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            
            Future<Integer> writeResult = channel.write(data, 0);
            writeResult.get();
        }
    }

    private Path getChunkPath(String worldDir, int x, int z) {
        int regionX = x >> 5;
        int regionZ = z >> 5;
        int chunkX = x & 31;
        int chunkZ = z & 31;
        
        Path regionDir = Paths.get(worldDir, "region");
        String regionFileName = "r." + regionX + "." + regionZ + ".mca";
        
        return regionDir.resolve(regionFileName);
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public void shutdown() {
        running = false;
        
        ioExecutor.shutdown();
        completionExecutor.shutdown();
        
        try {
            if (!ioExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
            if (!completionExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                completionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            completionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        pendingReads.values().forEach(f -> f.completeExceptionally(new IllegalStateException("AsyncChunkIO shutting down")));
        pendingWrites.values().forEach(f -> f.completeExceptionally(new IllegalStateException("AsyncChunkIO shutting down")));
        
        CoreSplitMod.LOGGER.info("[CoreSplit] AsyncChunkIO shutdown complete");
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveOperations() {
        return activeOps.get();
    }

    public int getPendingReadCount() {
        return pendingReads.size();
    }

    public int getPendingWriteCount() {
        return pendingWrites.size();
    }

    private static class IOThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        IOThreadFactory() {
            namePrefix = "coresplit-io-" + poolNumber.getAndIncrement() + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    private static class CompletionThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        CompletionThreadFactory() {
            namePrefix = "coresplit-io-completion-" + poolNumber.getAndIncrement() + "-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}