package com.coresplit.scheduler;

import com.coresplit.CoreSplitMod;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Priority-aware task scheduler for explosion batching and AI work.
 *
 * <p>Major improvements over the previous implementation:
 * <ul>
 *   <li><b>Bounded queue</b> capped at {@link #MAX_QUEUE_CAPACITY} to prevent OOM under sustained load
 *       (per project memory conventions).</li>
 *   <li><b>CallerRunsPolicy</b> instead of DiscardPolicy so saturated submissions run synchronously on
 *       the caller rather than being silently dropped.</li>
 *   <li><b>Runtime resizing</b> via {@link #applyThreadCount(int)} to react to config changes
 *       without a restart.</li>
 *   <li><b>Thread priority</b> bumped to NORM+1 so explosion/AI work wins contention with
 *       background cleanup.</li>
 *   <li><b>Non-daemon threads</b> with proper shutdown so in-flight tasks complete on exit.</li>
 * </ul>
 *
 * Priority convention: larger value = higher priority.
 *   PRIORITY_EXPLOSION_NEAR (100) > PRIORITY_AI (50) > PRIORITY_EXPLOSION_FAR (10).
 */
public class PrioritizedTaskScheduler {

    public static final int PRIORITY_EXPLOSION_NEAR = 100;
    public static final int PRIORITY_AI = 50;
    public static final int PRIORITY_EXPLOSION_FAR = 10;

    private static final int MIN_THREADS = 1;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final int MAX_QUEUE_CAPACITY = 50_000;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private final Object lock = new Object();
    private volatile ThreadPoolExecutor executor;
    private volatile int currentThreadCount;

    private final AtomicLong submittedCount = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private volatile boolean enabled = true;

    /**
     * Construct with a validated thread count derived from the caller's hardware profile.
     * Thread count is clamped to [1, HardwareDetector.getLogicalCores() × 2].
     */
    public PrioritizedTaskScheduler(int threads) {
        int maxHardware = Math.max(1, HardwareDetector.getLogicalCores() * 2);
        int safeThreads = Math.max(MIN_THREADS, Math.min(threads, maxHardware));
        this.currentThreadCount = safeThreads;
        this.executor = buildExecutor(safeThreads);
        CoreSplitMod.LOGGER.info("[CoreSplit] PrioritizedTaskScheduler started with {} threads (queueCap={})",
                safeThreads, MAX_QUEUE_CAPACITY);
    }

    private ThreadPoolExecutor buildExecutor(int threads) {
        // Use BoundedPriorityBlockingQueue: preserves priority ordering (via Comparable)
        // AND enforces a hard capacity so CallerRunsPolicy triggers when saturated.
        // Stock PriorityBlockingQueue is NOT truly bounded — its constructor arg is
        // only the initial internal array size. Our subclass overrides offer() and
        // remainingCapacity() to make the cap real, letting ThreadPoolExecutor's
        // rejection policy (CallerRunsPolicy) actually kick in under load while
        // preserving priority dequeue order.
        PriorityBlockingQueue<Runnable> queue = new BoundedPriorityBlockingQueue<>(MAX_QUEUE_CAPACITY);

        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                threads, threads,
                KEEP_ALIVE_SECONDS, TimeUnit.MILLISECONDS,
                queue,
                new PrioritizedThreadFactory(),
                // CallerRunsPolicy: when queue + pool are full, the submitter runs the task
                // inline. This provides natural back-pressure and avoids silent task loss.
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        tpe.allowCoreThreadTimeOut(true);
        tpe.prestartAllCoreThreads();
        return tpe;
    }

    /**
     * A subclass of {@link PriorityBlockingQueue} that enforces a hard capacity.
     * The stock PriorityBlockingQueue constructor argument is merely an
     * internal array-size hint and does not actually bound the queue, so
     * offer() never returns false. This subclass overrides offer and
     * remainingCapacity to make the cap real, which lets
     * {@link ThreadPoolExecutor}'s rejection policy (CallerRunsPolicy)
     * trigger under load — eliminating OOM risk while preserving priority
     * dequeue order via the underlying Comparable implementation of tasks.
     */
    private static final class BoundedPriorityBlockingQueue<E extends Runnable>
            extends PriorityBlockingQueue<E> {

        private final int capacity;

        BoundedPriorityBlockingQueue(int capacity) {
            super(Math.max(1, capacity));
            this.capacity = Math.max(1, capacity);
        }

        @Override
        public boolean offer(E e) {
            // Synchronize on the queue's monitor (same as the parent class
            // uses internally) so size-check + insert is atomic against
            // concurrent producers.
            synchronized (this) {
                if (size() >= capacity) {
                    return false; // trigger rejection policy in ThreadPoolExecutor
                }
                return super.offer(e);
            }
        }

        @Override
        public int remainingCapacity() {
            return Math.max(0, capacity - size());
        }
    }

    /**
     * Re-size the thread pool at runtime (e.g. from config UI changes).
     * @param threads new pool size; validated and clamped internally
     */
    public void applyThreadCount(int threads) {
        int maxHardware = Math.max(1, HardwareDetector.getLogicalCores() * 2);
        int safeThreads = Math.max(MIN_THREADS, Math.min(threads, maxHardware));
        synchronized (lock) {
            if (safeThreads == currentThreadCount) return;
            int oldCount = currentThreadCount;
            ThreadPoolExecutor old = executor;
            ThreadPoolExecutor next = buildExecutor(safeThreads);
            // Swap first so new submitters hit the resized pool immediately.
            this.executor = next;
            this.currentThreadCount = safeThreads;
            // Drain old executor gracefully — in-flight tasks complete, queue re-submits to new
            // pool are impossible (queue contents are Runnable not PrioritizedTask), so we let
            // the old executor finish its work asynchronously.
            if (old != null && !old.isShutdown()) {
                old.shutdown();
                CoreSplitMod.LOGGER.info("[CoreSplit] PrioritizedTaskScheduler resized: {} → {} threads",
                        oldCount, safeThreads);
            }
        }
    }

    public void submit(Runnable task, int priority) {
        if (!enabled) {
            runSafe(task);
            return;
        }
        submittedCount.incrementAndGet();
        ThreadPoolExecutor exec = executor;
        if (exec == null) {
            runSafe(task);
            return;
        }
        try {
            exec.execute(new PrioritizedTask(() -> {
                try {
                    task.run();
                } finally {
                    completedCount.incrementAndGet();
                }
            }, priority));
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to submit prioritized task: {}", e.getMessage());
            runSafe(task);
        }
    }

    public void submit(Runnable task) {
        submit(task, PRIORITY_AI);
    }

    public int getQueueSize() {
        ThreadPoolExecutor exec = executor;
        return exec == null ? 0 : exec.getQueue().size();
    }

    public long getSubmittedCount() { return submittedCount.get(); }
    public long getCompletedCount() { return completedCount.get(); }

    public int getActiveThreadCount() {
        ThreadPoolExecutor exec = executor;
        return exec == null ? 0 : exec.getActiveCount();
    }

    public int getPoolSize() {
        ThreadPoolExecutor exec = executor;
        return exec == null ? 0 : exec.getPoolSize();
    }

    public int getCurrentThreadCount() { return currentThreadCount; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    public void shutdown() {
        synchronized (lock) {
            enabled = false;
            ThreadPoolExecutor exec = executor;
            if (exec != null && !exec.isShutdown()) {
                exec.shutdown();
                try {
                    if (!exec.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        exec.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    exec.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
        CoreSplitMod.LOGGER.info("[CoreSplit] PrioritizedTaskScheduler shutdown (submitted={}, completed={}, poolSize={})",
                submittedCount.get(), completedCount.get(), currentThreadCount);
    }

    private void runSafe(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Prioritized task failed", e);
        }
    }

    private static final class PrioritizedTask implements Comparable<PrioritizedTask>, Runnable {
        private static final AtomicLong SEQ = new AtomicLong(0);
        private final Runnable delegate;
        private final int priority;
        private final long seq;

        PrioritizedTask(Runnable delegate, int priority) {
            this.delegate = delegate;
            this.priority = priority;
            this.seq = SEQ.getAndIncrement();
        }

        @Override
        public int compareTo(PrioritizedTask o) {
            int cmp = Integer.compare(o.priority, this.priority);
            return cmp != 0 ? cmp : Long.compare(this.seq, o.seq);
        }

        @Override
        public void run() {
            delegate.run();
        }
    }

    private static class PrioritizedThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        PrioritizedThreadFactory() {
            namePrefix = "coresplit-prio-" + poolNumber.getAndIncrement() + "-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            // Non-daemon so in-flight tasks complete during a clean shutdown. The shutdown
            // hook above calls shutdown() explicitly so the JVM won't hang.
            t.setDaemon(false);
            // NORM+1: explosion/AI tasks are latency-sensitive; they should win contention
            // against GC threads / debug monitors. MAX_PRIORITY is avoided to not starve
            // render/main threads.
            t.setPriority(Thread.NORM_PRIORITY + 1);
            if (HardwareDetector.isHybridSuspected()) {
                HardwareDetector.tryPinThreadToPerformanceCores(0x00FFL);
            }
            return t;
        }
    }
}

