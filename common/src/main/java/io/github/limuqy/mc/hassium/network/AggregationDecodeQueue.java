package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.Connection;

/**
 * 客户端聚合帧：Netty 入站只拷贝 byte[] 入队，每连接单工人 FIFO 解压拆包。
 * 工人调用 {@link PayloadHandlers#handleAggregation(byte[], Connection)}，
 * 子包 {@code packet.handle} 仍走原版 {@code PacketUtils} 跳主线程落地。
 */
public final class AggregationDecodeQueue {

    private static final ConcurrentHashMap<Connection, ConnState> STATES = new ConcurrentHashMap<>();
    private static final byte[] POISON = new byte[0];

    private AggregationDecodeQueue() {
    }

    public static void enqueue(Connection connection, byte[] data) {
        if (connection == null || data == null || data.length == 0) {
            return;
        }
        ConnState state = STATES.computeIfAbsent(connection, ConnState::new);
        state.ensureStarted();
        if (!state.alive.get()) {
            return;
        }
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        state.queue.offer(copy);
    }

    /** Loader fallback：从当前客户端连接入队（Mixin 已拦截时不会走到这里）。 */
    public static void enqueueClient(byte[] data) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        var listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        enqueue(listener.getConnection(), data);
    }

    public static void discard(Connection connection) {
        if (connection == null) {
            return;
        }
        ConnState state = STATES.remove(connection);
        if (state == null) {
            return;
        }
        state.alive.set(false);
        state.queue.clear();
        state.queue.offer(POISON);
    }

    private static final class ConnState {
        private final Connection connection;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean started = new AtomicBoolean(false);

        private ConnState(Connection connection) {
            this.connection = connection;
        }

        void ensureStarted() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            Thread worker = new Thread(this::loop, "Hassium-AggDecode");
            worker.setDaemon(true);
            worker.start();
        }

        private void loop() {
            try {
                while (alive.get() || !queue.isEmpty()) {
                    byte[] data;
                    try {
                        data = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (data == POISON || data.length == 0) {
                        return;
                    }
                    try {
                        PayloadHandlers.handleAggregation(data, connection);
                    } catch (Throwable t) {
                        Constants.LOG.error("Hassium: aggregation decode worker failed", t);
                    }
                }
            } finally {
                STATES.remove(connection, this);
            }
        }
    }
}
