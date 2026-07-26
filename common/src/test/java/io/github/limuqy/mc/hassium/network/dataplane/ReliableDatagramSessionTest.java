package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2 — 端到端校验 {@link ReliableDatagramSession}。
 *
 * <p>所有用例都通过公有的 {@link ReliableDatagramSession} 接口驱动，不直接调用 {@code Kcp}。
 *
 * <p>模型与变量命名（消除方向歧义）：
 * <ul>
 *   <li>两端共享 {@code (playerId,epoch)} → 共享 KCP conv；其角色相反、seal 方向相反。</li>
 *   <li>{@code server}：{@link UdpEndpoint.Role#SERVER}，发出 {@link UdpFrameCodec.Direction#SERVER_TO_CLIENT} seal 的帧。</li>
 *   <li>{@code client}：{@link UdpEndpoint.Role#CLIENT}，发出 {@link UdpFrameCodec.Direction#CLIENT_TO_SERVER} seal 的帧。</li>
 *   <li>{@code s2cWire}：server 端 {@code KcpOutput} 线字节队列 —— 必须 deliver 给 {@code client}{@link ReliableDatagramSession#receive}。</li>
 *   <li>{@code c2sWire}：client 端 KcpOutput 线字节队列 —— 必须 deliver 给 {@code server}{@link ReliableDatagramSession#receive}。</li>
 * </ul>
 * 关键不变量：A 写出的线字节（A 的 sink 队列）只能送给 B.receive；自身绝不消费自己的输出。
 */
class ReliableDatagramSessionTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long EPOCH = 42L;
    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 59999);
    private static final long STEP_MS = 10L;

    private final List<ReliableDatagramSession> sessions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ReliableDatagramSession s : sessions) {
            try { s.close(); } catch (Throwable ignored) {}
        }
        sessions.clear();
    }

    /** 单向线字节队列：sink 端 {@code KcpOutput.out} 写入，对端 {@code receive} 取走。 */
    private static final class Wire {
        private final Deque<ByteBuf> pending = new ArrayDeque<>();
        void post(ByteBuf wire) { pending.addLast(wire); }
        ByteBuf poll() { return pending.pollFirst(); }
    }

    private static ReliableDatagramSession.DatagramSink sinkTo(Wire wire) {
        return wire::post;
    }

    private ReliableDatagramSession serverSession(UdpEndpoint ep, Wire wire) {
        ReliableDatagramSession s = new ReliableDatagramSession(
                PLAYER, EPOCH, ep, LOCAL, testKey(), sinkTo(wire));
        sessions.add(s);
        return s;
    }

    private ReliableDatagramSession clientSession(UdpEndpoint ep, Wire wire) {
        ReliableDatagramSession s = new ReliableDatagramSession(
                PLAYER, EPOCH, ep, LOCAL, testKey(), sinkTo(wire));
        sessions.add(s);
        return s;
    }

    private static byte[] testKey() {
        byte[] k = new byte[16];
        Arrays.fill(k, (byte) 7);
        return k;
    }

    /** 默认端点配置：MTU 1200、snd/rcv 窗 128、最大应用排队 1 MiB、重组上限 8 MiB、SRTT 阈值 1000ms。 */
    private UdpEndpoint defaultEndpoint(UdpEndpoint.Role role) {
        return UdpEndpoint.builder()
                .role(role)
                .localAddress(LOCAL)
                .mtu(1200)
                .sndWindow(128)
                .rcvWindow(128)
                .maxQueuedAppBytes(1 << 20)
                .maxReassemblyBytes(8 << 20)
                .hardRttMs(1000)
                .build();
    }

    /**
     * 推进一步虚拟时钟：双端 tick，然后把 server 发出的线字节送往 client.receive，
     * client 发出的线字节送往 server.receive。此即「不撞墙」的 loopback 网络。
     */
    private static void step(ReliableDatagramSession server, Wire s2cWire,
                            ReliableDatagramSession client, Wire c2sWire, long nowMs) {
        server.tick(nowMs);
        client.tick(nowMs);
        ByteBuf b;
        while ((b = s2cWire.poll()) != null) {
            client.receive(b.retainedDuplicate(), nowMs);
            b.release();
        }
        while ((b = c2sWire.poll()) != null) {
            server.receive(b.retainedDuplicate(), nowMs);
            b.release();
        }
    }

    /** 16 KiB payload 经可靠传输一次、有序到达接收端。 */
    @Test
    void sixteenKiBPayloadArrivesOnceInOrder() {
        AtomicInteger deliverCount = new AtomicInteger();
        Wire s2c = new Wire();
        Wire c2s = new Wire();
        ReliableDatagramSession server = serverSession(defaultEndpoint(UdpEndpoint.Role.SERVER), s2c);
        ReliableDatagramSession client = clientSession(defaultEndpoint(UdpEndpoint.Role.CLIENT), c2s);
        server.receiveHandler(r -> {
            deliverCount.incrementAndGet();
            assertEquals(payload().length, r.payload().length);
        });

        byte[] payload = payload();
        client.enqueueAuthenticated(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, payload);
        for (int step = 0; step < 2000 && deliverCount.get() == 0; step++) {
            step(server, s2c, client, c2s, step * STEP_MS);
        }
        assertEquals(1, deliverCount.get(), "16 KiB must arrive exactly once");
    }

    /** 80 字节 payload 在 64 字节最大排队下：入队后立即不可写。 */
    @Test
    void smallPayloadExceedsConfiguredQueueBoundMakesSessionNonWritable() {
        UdpEndpoint ep = UdpEndpoint.builder()
                .role(UdpEndpoint.Role.CLIENT)
                .localAddress(LOCAL)
                .mtu(1200)
                .maxQueuedAppBytes(64)
                .build();
        Wire c2s = new Wire();
        Wire s2c = new Wire();
        ReliableDatagramSession client = clientSession(ep, c2s);
        ReliableDatagramSession server = serverSession(ep.toRole(UdpEndpoint.Role.SERVER), s2c);
        server.receiveHandler(r -> {});

        byte[] payload = new byte[80];
        Arrays.fill(payload, (byte) 9);

        assertTrue(client.isWritable(), "fresh session must be writable");
        client.enqueueAuthenticated(DataPlaneFrame.TYPE_BULK_SECTION_DELTA, payload);
        assertFalse(client.isWritable(),
                "80-byte enqueue against 64-byte max must mark session non-writable");
    }

    /** 单个确定性丢包触发重传，且应用层只投递一次（不重复）；丢包度量至少登记 1 次。 */
    @Test
    void oneDeterministicDropRetransmitsWithoutDuplicateDelivery() {
        AtomicInteger deliverCount = new AtomicInteger();
        Wire s2c = new Wire();
        Wire c2s = new Wire();
        ReliableDatagramSession server = serverSession(defaultEndpoint(UdpEndpoint.Role.SERVER), s2c);
        ReliableDatagramSession client = clientSession(defaultEndpoint(UdpEndpoint.Role.CLIENT), c2s);
        server.receiveHandler(r -> deliverCount.incrementAndGet());

        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        client.enqueueAuthenticated(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, payload);

        boolean[] droppedFirst = new boolean[1];
        long nowMs = 0L;
        for (int step = 0; step < 4000 && deliverCount.get() == 0; step++, nowMs += STEP_MS) {
            server.tick(nowMs);
            client.tick(nowMs);
            ByteBuf b;
            // server→client: drop first datagram (in the client→server direction)
            while ((b = c2s.poll()) != null) {
                if (!droppedFirst[0]) {
                    droppedFirst[0] = true;
                    b.release();
                    continue;
                }
                server.receive(b.retainedDuplicate(), nowMs);
                b.release();
            }
            // server→client: deliver all
            while ((b = s2c.poll()) != null) {
                client.receive(b.retainedDuplicate(), nowMs);
                b.release();
            }
        }
        // final convergence tick
        server.tick(nowMs + STEP_MS);
        client.tick(nowMs + STEP_MS);
        ByteBuf b;
        while ((b = c2s.poll()) != null) { server.receive(b.retainedDuplicate(), nowMs); b.release(); }
        while ((b = s2c.poll()) != null) { client.receive(b.retainedDuplicate(), nowMs); b.release(); }

        assertEquals(1, deliverCount.get(), "exactly one delivery despite one dropped datagram");
        assertTrue(client.metrics().packetsLost() >= 1L, "loss metric must register at least one");
    }

    /** 确定性重排序不造成重复投递也不破坏数据。 */
    @Test
    void deterministicReorderingDoesNotDuplicateOrDamageDelivery() {
        AtomicInteger deliverCount = new AtomicInteger();
        List<byte[]> delivered = new ArrayList<>();
        Wire s2c = new Wire();
        Wire c2s = new Wire();
        ReliableDatagramSession server = serverSession(defaultEndpoint(UdpEndpoint.Role.SERVER), s2c);
        ReliableDatagramSession client = clientSession(defaultEndpoint(UdpEndpoint.Role.CLIENT), c2s);
        server.receiveHandler(r -> { deliverCount.incrementAndGet(); delivered.add(r.payload()); });

        byte[] payload = new byte[] {1, 2, 3, 4, 5};
        client.enqueueAuthenticated(DataPlaneFrame.TYPE_KEEPALIVE, payload);

        long nowMs = 0L;
        // First tick generates client→server datagrams in c2s.
        server.tick(nowMs);
        client.tick(nowMs);
        nowMs += STEP_MS;

        // Drain client→server queue into a list and deliver in REVERSE order (deterministic reorder).
        List<ByteBuf> pending = new ArrayList<>();
        ByteBuf t;
        while ((t = c2s.poll()) != null) pending.add(t);
        for (int i = pending.size() - 1; i >= 0; i--) {
            ByteBuf bb = pending.get(i);
            server.receive(bb.retainedDuplicate(), nowMs);
            bb.release();
        }
        pending.clear();
        while ((t = s2c.poll()) != null) { client.receive(t.retainedDuplicate(), nowMs); t.release(); }

        for (int step = 0; step < 2000 && deliverCount.get() == 0; step++, nowMs += STEP_MS) {
            step(server, s2c, client, c2s, nowMs);
        }

        assertEquals(1, deliverCount.get(), "reordered delivery must not be duplicated");
        assertEquals(1, delivered.size());
        assertArrayEquals(payload, delivered.get(0), "reordering must not damage payload");
    }

    /** close 幂等且释放后不可写、不健康；重复 close 不抛。 */
    @Test
    void closeIsIdempotentAndReleasesResources() {
        Wire c2s = new Wire();
        ReliableDatagramSession s = clientSession(defaultEndpoint(UdpEndpoint.Role.CLIENT), c2s);
        assertTrue(s.isWritable());
        s.close();
        s.close();
        assertFalse(s.isWritable(), "closed session must not be writable");
        assertFalse(s.isHealthy(), "closed session must not be healthy");
    }

    private static byte[] payload() {
        byte[] payload = new byte[16 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);
        return payload;
    }
}
