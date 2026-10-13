package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.Constants;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;

/**
 * 客户端聚合帧：Netty 入站只拷贝 byte[] 入队，每连接单工人 FIFO 解压拆包。
 * 工人调用 {@link PayloadHandlers#handleAggregation(byte[], Connection)}，
 * 子包 {@code packet.handle} 仍走原版 {@code PacketUtils} 跳主线程落地。
 */
public final class AggregationDecodeQueue {

    private static final ConcurrentHashMap<Connection, ConnState> STATES = new ConcurrentHashMap<>();
    private static final Object POISON = new Object();

    private AggregationDecodeQueue() {
    }

    public static void enqueue(Connection connection, byte[] data) {
        if (connection == null || data == null || data.length == 0) {
            return;
        }
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        enqueueOrdered(connection, copy, true);
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

    /** The frame worker must submit its preceding subpackets before this bundle's main-thread hop. */
    public static boolean enqueueBundle(Connection connection, net.minecraft.network.protocol.Packet<?> packet) {
        return enqueueOrdered(connection, packet, false);
    }

    static boolean enqueueOrdered(Connection connection, Object packet, boolean create) {
        ConnState state = create ? STATES.computeIfAbsent(connection, ConnState::new) : STATES.get(connection);
        if (state == null || !state.alive.get()) {
            return false;
        }
        long gen = state.epoch.get();
        state.queue.offer(packet);
        if (gen != state.epoch.get()) {
            state.queue.remove(packet);
        }
        state.ensureStarted();
        return true;
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
        state.epoch.incrementAndGet();
        state.queue.clear();
        state.queue.offer(POISON);
    }

    private static final class ConnState {
        private final Connection connection;
        private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean started = new AtomicBoolean(false);
        /** 代际：discard 时递增；enqueue 用它在 offer 前后识别「复活死 ConnState」与 POISON 错序（review §2.8）。 */
        private final AtomicLong epoch = new AtomicLong();

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
                    Object data;
                    try {
                        data = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (data == POISON || !alive.get()) {
                        // 竞态③：死连接排空——discard 后残留帧直接丢弃退出，不分发到已断连接
                        return;
                    }
                    try {
                        if (data instanceof byte[] frame) {
                            PayloadHandlers.handleAggregation(frame, connection);
                        } else if (data instanceof Runnable ordered) {
                            ordered.run();
                        } else {
                            @SuppressWarnings("rawtypes")
                            net.minecraft.network.protocol.Packet packet =
                                    (net.minecraft.network.protocol.Packet) data;
                            packet.handle(connection.getPacketListener());
                        }
                    } catch (net.minecraft.server.RunningOnDifferentThreadException ignored) {
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
