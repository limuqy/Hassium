package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端 shadow_pull 响应专用 hop：接收侧只拷贝 byte[] 入队，单工人 FIFO 做
 * ZSTD 解压 + packet 解码 + 业务分发。落地仍由 {@code ShadowPullClient} 内
 * {@code mc.execute} 回主线程。
 * <p>
 * 与 {@link AggregationDecodeQueue} 同构但全局单工人：Pull 响应延迟敏感，
 * 不按连接分片；聚合帧内的 pull 子包也经本队列串行，避免与直发路径并行踩
 * 同一柱在途状态。
 */
public final class PullResponseDecodeQueue {

    private static final byte[] POISON = new byte[0];
    private static final LinkedBlockingQueue<byte[]> QUEUE = new LinkedBlockingQueue<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean ALIVE = new AtomicBoolean(true);

    private PullResponseDecodeQueue() {
    }

    public static void enqueue(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        ensureStarted();
        if (!ALIVE.get()) {
            return;
        }
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        QUEUE.offer(copy);
    }

    /** 断连：丢弃积压并停工人；下次 enqueue 会重启。 */
    public static void discard() {
        ALIVE.set(false);
        QUEUE.clear();
        QUEUE.offer(POISON);
    }

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        ALIVE.set(true);
        Thread worker = new Thread(PullResponseDecodeQueue::loop, "Hassium-PullDecode");
        worker.setDaemon(true);
        worker.start();
    }

    private static void loop() {
        try {
            while (ALIVE.get() || !QUEUE.isEmpty()) {
                byte[] data;
                try {
                    data = QUEUE.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (data == POISON || data.length == 0) {
                    return;
                }
                try {
                    PayloadHandlers.processShadowPullResponse(data);
                } catch (Throwable t) {
                    Constants.LOG.error("Hassium: pull response decode worker failed", t);
                }
            }
        } finally {
            STARTED.set(false);
        }
    }
}
