package com.coresplit.threading;

import com.coresplit.CoreSplitMod;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阶段同步屏障。
 *
 * 功能类似 CyclicBarrier，但支持动态调整 parties 数量
 * （因为每个 Tick 可能有不同数量的活跃维度）。
 *
 * 工作原理：
 *   1. 主线程调用 await(expectedParties)，阻塞等待
 *   2. 每个维度线程完成后调用 arrive()，计数器递减
 *   3. 当计数器归零，所有线程被唤醒
 *   4. 超时保护：若某个维度卡死，超时后强制放行
 */
public class PhaseBarrier {

    private final int defaultTimeoutMs;
    private final AtomicInteger arriveCount = new AtomicInteger(0);
    private final Object lock = new Object();
    private volatile int expectedParties = 0;
    private volatile boolean phaseComplete = false;

    public PhaseBarrier(int defaultParties, int timeoutMs) {
        this.defaultTimeoutMs = timeoutMs;
        this.expectedParties = defaultParties;
    }

    /**
     * 维度线程调用 — 通知一个参与者已完成。
     */
    public void arrive() {
        int remaining = arriveCount.decrementAndGet();
        if (remaining <= 0) {
            synchronized (lock) {
                phaseComplete = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * 主线程调用 — 等待所有参与者完成。
     */
    public void await(int parties) {
        arriveCount.set(parties);
        phaseComplete = false;
        expectedParties = parties;

        // 主线程自己也算一个参与者
        arriveCount.decrementAndGet();

        synchronized (lock) {
            long deadline = System.currentTimeMillis() + defaultTimeoutMs;
            while (!phaseComplete) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] Phase barrier timeout! {} parties still pending.",
                            arriveCount.get());
                    break;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 重置
        arriveCount.set(0);
        phaseComplete = false;
    }
}