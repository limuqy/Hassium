package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    /**
     * review-fix: T4-87 — datagram 级 4B 截断 HMAC 标签：无标签 / 篡改标签的 KCP 线字节在进入 KCP 前
     * 被静默丢弃；仅带正确标签的 datagram 被投递（防伪造分片灌满重组窗口）。
     */
    @Test
    void datagramTagIsRequiredAndVerified() {
        AtomicInteger deliverCount = new AtomicInteger();
        Wire s2c = new Wire();
        Wire c2s = new Wire();
        ReliableDatagramSession server = serverSession(defaultEndpoint(UdpEndpoint.Role.SERVER), s2c);
        ReliableDatagramSession client = clientSession(defaultEndpoint(UdpEndpoint.Role.CLIENT), c2s);
        server.receiveHandler(r -> deliverCount.incrementAndGet());

        byte[] payload = new byte[] {1, 2, 3};
        client.enqueueAuthenticated(DataPlaneFrame.TYPE_KEEPALIVE, payload);

        long nowMs = 0L;
        ByteBuf wire = null;
        for (int i = 0; i < 200 && wire == null; i++, nowMs += STEP_MS) {
            server.tick(nowMs);
            client.tick(nowMs);
            wire = c2s.poll();
        }
        assertNotNull(wire, "client must emit tagged datagrams");
        try {
            int len = wire.readableBytes();
            assertTrue(len > 4, "tagged datagram must carry KCP bytes plus 4B tag");

            // (1) 去掉尾部标签 → 丢弃（不进 KCP）
            ByteBuf untagged = PooledByteBufAllocator.DEFAULT.buffer(len - 4);
            untagged.writeBytes(wire, wire.readerIndex(), len - 4);
            server.receive(untagged, nowMs);

            // (2) 篡改标签末字节 → 丢弃（独立拷贝，不污染 wire 共享内存）
            ByteBuf corrupt = PooledByteBufAllocator.DEFAULT.buffer(len);
            corrupt.writeBytes(wire, wire.readerIndex(), len);
            corrupt.setByte(corrupt.writerIndex() - 1, corrupt.getByte(corrupt.writerIndex() - 1) ^ 0x01);
            server.receive(corrupt, nowMs);

            // (3) 原样标签 → 投递
            server.receive(wire.retainedDuplicate(), nowMs);
        } finally {
            wire.release();
        }

        for (int step = 0; step < 2000 && deliverCount.get() == 0; step++) {
            step(server, s2c, client, c2s, (step + 1) * STEP_MS);
        }
        assertEquals(1, deliverCount.get(), "only the validly tagged datagram may be delivered");
        drainWire(s2c);
        drainWire(c2s);
    }

    private static byte[] payload() {
        byte[] payload = new byte[16 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);
        return payload;
    }

    // ---------- Task 2 fix tests (F1, F2) ----------

    /**
     * F1 — KCP flush 回调 {@code KcpOutput.out(ByteBuf, Kcp)} 返回后不 release 原 buf（已由 javap 确认），
     * 故 {@link ReliableDatagramSession#onKcpOutput} 必须释放之。本测试用「零缓存专用 {@link PooledByteBufAllocator}」
     * 的 direct arena {@code numActiveAllocations()} 精确统计当前未释放的 direct buffer：
     * 零缓存使每次 alloc/dealloc 都落 arena，{@code numActiveAllocations == alloc - dealloc} 即泄漏计数；
     * 通过 {@code kcp.setByteBufAllocator(alloc)}（构造期已 wire 进会话）把 KCP flush 累加 buf 也纳入观测。
     *
     * <p>为什么不沿用 brief 字面推荐的「sample dup refCnt」：Netty 4.1.x 起 pooled {@code retainedDuplicate()}
     * 采用 per-branch refCnt，sink 收到的 dup 其 refCnt 始终为 1，泄漏/正确两实现不可区分；改用
     * arena {@code numActiveAllocations} 直接观测「原 buf 是否被 release」，确定性且无 OS 缓存噪声。
     */
    @Test
    void kcpOutputReleasesOriginalBuffer() {
        // 零缓存的专用 pooled direct allocator：cache 全 0 → 每次 alloc/dealloc 都触及 arena；
        // directArenas().get(0).numActiveAllocations() = 当前尚未 dealloc 的 direct buffer 数（即「泄漏」计数）。
        PooledByteBufAllocator leakProbe =
                new PooledByteBufAllocator(true, 1, 1, 8192, 11, 0, 0, 0);
        ReliableDatagramSession.ALLOC_OVERRIDE = leakProbe;
        try {
            AtomicInteger deliverCount = new AtomicInteger();
            Wire s2c = new Wire();
            Wire c2s = new Wire();
            ReliableDatagramSession server = serverSession(defaultEndpoint(UdpEndpoint.Role.SERVER), s2c);
            ReliableDatagramSession client = clientSession(defaultEndpoint(UdpEndpoint.Role.CLIENT), c2s);
            server.receiveHandler(r -> deliverCount.incrementAndGet());

            // 连续入队若干条小帧，强制触发多次 KCP flush（每次 flush 至少产出一个累加 buf）。
            for (int i = 0; i < 16; i++) {
                byte[] p = new byte[64];
                Arrays.fill(p, (byte) (i & 0xFF));
                client.enqueueAuthenticated(DataPlaneFrame.TYPE_KEEPALIVE, p);
            }

            // pump loopback to idle；期望 16 条全部投递。step 会对线字节 retainedDuplicate 后交给对端 receive
            // 并释放本侧 dup（dup 是走服务端 ByteBufAllocator.DEFAULT 的裸 pooled buffer，与 leakProbe 无关）。
            long nowMs = 0L;
            for (int step = 0; step < 6000 && deliverCount.get() < 16; step++, nowMs += STEP_MS) {
                step(server, s2c, client, c2s, nowMs);
            }
            assertEquals(16, deliverCount.get(),
                "16 small frames must all arrive exactly once before checking live buffer count");

            // 让 in-flight 重传/ACK 收敛，再双端 close（close 调 kcp.release() 释放记录队列里的 segment data buffer，
            // 这些都会被 arena 计为 dealloc，不计入泄漏）。
            for (int step = 0; step < 300; step++, nowMs += STEP_MS) {
                step(server, s2c, client, c2s, nowMs);
                if (c2s.pending.isEmpty() && s2c.pending.isEmpty()) break;
            }
            server.close();
            client.close();
            drainWire(s2c);
            drainWire(c2s);
            long active = leakProbe.metric().directArenas().get(0).numActiveAllocations();

            // 关键断言：正确实现下每个 flush 累加 buf 都被 onKcpOutput 内 release（外加 segment data 被 close 释放）
            // → direct arena active == 0；泄漏实现（不 release buf）下这些 flush 累加 buf 永远 live → active > 0。
            assertEquals(0L, active,
                "every KCP flush accumulator buffer must be released by onKcpOutput "
                    + "(leaked direct buffers still active in probe arena: " + active + ")");
        } finally {
            ReliableDatagramSession.ALLOC_OVERRIDE = null;
        }
    }

    /** 释放 Wire 残留线字节，避免误计。 */
    private static void drainWire(Wire w) {
        ByteBuf b;
        while ((b = w.poll()) != null) {
            try { b.release(); } catch (Throwable ignored) {}
        }
    }


    /**
     * 围绕 {@link PooledByteBufAllocator#DEFAULT} 的计数包装：统计 {@code initialCapacity >= bigThreshold}
     * 的 direct 申请次数。F2 测试用其区分「丢弃路径 {@code alloc.buffer(peek)}（leaky 多一次）」与
     * 「入队时 seal 帧 buffer（固定一次/每帧）」——两者 initialCapacity 同为 sealed 长度，故按「超限次数」
     * 二选一计数：fixed 下每帧只产生 1 次（seal buffer）；leaky 下每帧 2 次（seal buffer + discard buffer）。
     * direct-only 计数是因为 F1 已把 KCP 分片 buffer 也路由进 {@code alloc}，
     * {@code alloc.buffer()} 仍是 default-heap。
     */
    private static final class CountingAllocator implements ByteBufAllocator {
        private final ByteBufAllocator delegate;
        private final int bigThreshold;
        private int bigDirectAllocations = 0;
        private int maxRequestedCapacity = 0;

        CountingAllocator(ByteBufAllocator delegate, int bigThreshold) {
            this.delegate = delegate;
            this.bigThreshold = bigThreshold;
        }

        int bigDirectAllocations() { return bigDirectAllocations; }
        int maxRequestedCapacity() { return maxRequestedCapacity; }

        private ByteBuf record(int initialCapacity, int maxCapacity, boolean direct) {
            maxRequestedCapacity = Math.max(maxRequestedCapacity, initialCapacity);
            if (direct && initialCapacity >= bigThreshold) {
                bigDirectAllocations++;
            }
            return direct ? delegate.directBuffer(initialCapacity, maxCapacity)
                          : delegate.heapBuffer(initialCapacity, maxCapacity);
        }

        // 仅 direct 路径计入「超大」统计；heap 路径（sealed frame working buffer）透传默认 heap allocator。
        // PooledByteBufAllocator.DEFAULT 默认 preferDirect=true → alloc.buffer(int) 返回 direct，故 buffer() 也走 direct 计数与发行。
        @Override public ByteBuf buffer() { return record(256, Integer.MAX_VALUE, delegate.isDirectBufferPooled()); }
        @Override public ByteBuf buffer(int initialCapacity) {
            return record(initialCapacity, Math.max(initialCapacity, Integer.MAX_VALUE - 8), delegate.isDirectBufferPooled());
        }
        @Override public ByteBuf buffer(int initialCapacity, int maxCapacity) {
            return record(initialCapacity, maxCapacity, delegate.isDirectBufferPooled());
        }
        @Override public ByteBuf ioBuffer() { return record(256, Integer.MAX_VALUE, true); }
        @Override public ByteBuf ioBuffer(int initialCapacity) {
            return record(initialCapacity, Math.max(initialCapacity, Integer.MAX_VALUE - 8), true);
        }
        @Override public ByteBuf ioBuffer(int initialCapacity, int maxCapacity) {
            return record(initialCapacity, maxCapacity, true);
        }
        @Override public ByteBuf heapBuffer() { return record(256, Integer.MAX_VALUE, false); }
        @Override public ByteBuf heapBuffer(int initialCapacity) {
            return record(initialCapacity, Math.max(initialCapacity, Integer.MAX_VALUE - 8), false);
        }
        @Override public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
            return record(initialCapacity, maxCapacity, false);
        }
        @Override public ByteBuf directBuffer() { return record(256, Integer.MAX_VALUE, true); }
        @Override public ByteBuf directBuffer(int initialCapacity) {
            return record(initialCapacity, Math.max(initialCapacity, Integer.MAX_VALUE - 8), true);
        }
        @Override public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
            return record(initialCapacity, maxCapacity, true);
        }
        @Override public io.netty.buffer.CompositeByteBuf compositeBuffer() { return delegate.compositeBuffer(); }
        @Override public io.netty.buffer.CompositeByteBuf compositeBuffer(int maxNumComponents) {
            return delegate.compositeBuffer(maxNumComponents);
        }
        @Override public io.netty.buffer.CompositeByteBuf compositeHeapBuffer() { return delegate.compositeHeapBuffer(); }
        @Override public io.netty.buffer.CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
            return delegate.compositeHeapBuffer(maxNumComponents);
        }
        @Override public io.netty.buffer.CompositeByteBuf compositeDirectBuffer() { return delegate.compositeDirectBuffer(); }
        @Override public io.netty.buffer.CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
            return delegate.compositeDirectBuffer(maxNumComponents);
        }
        @Override public boolean isDirectBufferPooled() { return delegate.isDirectBufferPooled(); }
        @Override public int calculateNewCapacity(int minNewCapacity, int maxCapacity) {
            return delegate.calculateNewCapacity(minNewCapacity, maxCapacity);
        }
    }

    /**
     * F2 — 重组上限 PREVENTIVE：拒绝超大消息时绝不先申请 peek-sized buffer。
     * 配 maxReassemblyBytes=1024，投递 8 条 4096 字节已鉴权（超限）帧，断言：
     * (a) 无一条投递给 Consumer；
     * (b) 「>= 4096 字节」的 direct 申请次数恰好等于入队次数 8 —— 即每帧只产生 seal 工作分配，
     *     而 leaky 路径每帧多一次 {@code alloc.buffer(peek)} 的丢弃分配（= 16，测试失败）；
     *     fixed 丢弃路径走 {@code kcp.recv(List)} 不新增 direct 分配；
     * (c) 会话仍可写、健康，且后续一条正常帧仍被投递。
     */
    @Test
    void oversizedReassemblyIsDrainedWithoutLargeAllocation() {
        // bigThreshold=4096：4096B payload 经 seal 后约 4129B（>=4096），sealBuffer 与 leaky discardBuffer 都命中阈值；
        // fixed 的 discard 走 kcp.recv(List) 不 alloc.buffer(peek)，故只数到 1 次（seal）/帧；leaky 每帧 2 次 → 测试失败。
        CountingAllocator counting = new CountingAllocator(PooledByteBufAllocator.DEFAULT, 4096);
        ReliableDatagramSession.ALLOC_OVERRIDE = counting;
        try {
            final int frames = 8;
            AtomicInteger deliverCount = new AtomicInteger();
            UdpEndpoint overszEp = UdpEndpoint.builder()
                    .role(UdpEndpoint.Role.CLIENT)
                    .localAddress(LOCAL)
                    .mtu(1200)
                    .sndWindow(128)
                    .rcvWindow(128)
                    .maxQueuedAppBytes(1 << 20)
                    .maxReassemblyBytes(1024)
                    .hardRttMs(1000)
                    .build();
            Wire s2c = new Wire();
            Wire c2s = new Wire();
            ReliableDatagramSession client = clientSession(overszEp, c2s);
            ReliableDatagramSession server = serverSession(overszEp.toRole(UdpEndpoint.Role.SERVER), s2c);
            server.receiveHandler(r -> deliverCount.incrementAndGet());

            byte[] big = new byte[4096];
            for (int i = 0; i < big.length; i++) big[i] = (byte) (i & 0xFF);
            for (int i = 0; i < frames; i++) {
                client.enqueueAuthenticated(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, big);
            }

            // pump loopback：超大消息被分片经 KCP 到达 server 后 drainReceived 应识别超限并丢弃而绝不投递。
            long nowMs = 0L;
            for (int step = 0; step < 6000 && deliverCount.get() == 0; step++, nowMs += STEP_MS) {
                step(server, s2c, client, c2s, nowMs);
            }
            assertEquals(0, deliverCount.get(),
                "8 oversized 4096-byte messages must NOT be delivered under maxReassemblyBytes=1024");

            // (b) 关键断言：fixed 丢弃路径走 kcp.recv(List) 不再 alloc.buffer(peek)→ 每帧 direct「超大」申请仅 1 次（sealBuffer）；
            // leaky 路径每帧多 1 次 alloc.buffer(peek) 的丢弃分配 → 2 倍于 frames，断言失败。
            assertEquals(frames, counting.bigDirectAllocations(),
                "discard path must not allocate a peek-sized direct buffer (expected " + frames
                    + " seal allocations, got " + counting.bigDirectAllocations()
                    + " — each oversized message leaked one alloc.buffer(peek) in the discard path)");

            // (c) 会话仍可写、健康，且后续一条正常帧仍被投递。
            assertTrue(client.isWritable(), "session must remain writable after oversized drain");
            assertTrue(client.isHealthy(), "session must remain healthy after oversized drain");

            AtomicInteger secondCount = new AtomicInteger();
            server.receiveHandler(r -> secondCount.incrementAndGet());
            byte[] tiny = new byte[32];
            Arrays.fill(tiny, (byte) 0x33);
            client.enqueueAuthenticated(DataPlaneFrame.TYPE_KEEPALIVE, tiny);
            long nowMs2 = nowMs;
            for (int step = 0; step < 4000 && secondCount.get() == 0; step++, nowMs2 += STEP_MS) {
                step(server, s2c, client, c2s, nowMs2);
            }
            assertEquals(1, secondCount.get(),
                "a subsequent normal frame must still be delivered after oversized drain");
        } finally {
            ReliableDatagramSession.ALLOC_OVERRIDE = null;
        }
    }
}
