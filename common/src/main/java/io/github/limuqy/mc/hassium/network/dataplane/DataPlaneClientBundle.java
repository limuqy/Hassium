package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task 5 — 客户端 UDP 数据面 bundle（替换 PoC TCP {@code DataPlaneClientBundle}）。
 *
 * <p><b>职责不变量</b>：每个 advertised listener group 最多对应一个 {@link ReliableDatagramSession}。
 * 同组 reachable candidates 严格串行；只有收到经 session 加密且 epoch/endpointId 匹配的
 * {@link DataPlaneFrame#TYPE_BIND_ACK} 后才可路由 bulk。
 * 双向认证；解出的 {@link DataPlaneFrame#TYPE_BULK_COMPRESSED_CHUNK} 在 UDP 事件循环上仅在注入的
 * chunk 消费者上调度 {@link ClientChunkHandler#handleCompressedChunk(byte[])}，绝不直接进入 MC 主线程。
 *
 * <p><b>PoC 静态计数器兼容</b>：保留 {@code getBulkFramesData()/getBulkBytesData()/
 * snapshotPerPort()/resetDataBulkCounters()} 形态不变，让冒烟与 Guard 测试无需感知 transport 切换；
 * 内部计数仍按 endpointId（被当作之前的 1-based portIdx）累积。
 *
 * <p><b>不变量</b>：所有 KV （endpointId → ReliableDatagramSession）都靠单 shared worker group。
 * 关闭 ({@link #shutdown()}) 幂等 — 关 channel、关 group、关每个 session、清 map，但不清零
 * 全局 counter（caller 用 {@link #resetDataBulkCounters()} 显式清，保留 PoC 行为）。
 * 收到的 chunk 走 inject 的 {@code ChunkDispatcher}；缺省 dispatcher 直接调
 * {@link ClientChunkHandler#handleCompressedChunk(byte[])} 以兼容 PoC caller。
 */
public final class DataPlaneClientBundle {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneClient");

    /** BindRequest protocol version aligned with {@link UdpDataPlaneHandshakeTail#PROTOCOL_VERSION}. */
    private static final int PROTOCOL_VERSION = UdpBindRequestCodec.PROTOCOL_VERSION;

    // ===== PoC-compatible static counters (transport-agnostic) =====
    // review-fix: T4-86 — volatile long 非原子 ++/+= 在 workerGroup 双线程 event loop 并发累计会丢计数；
    // 改 AtomicLong 保证用户可见指标准确（getter/reset 形态不变）。
    public static final AtomicLong bulkFramesData = new AtomicLong(0);
    public static final AtomicLong bulkBytesData = new AtomicLong(0);
    private static final ConcurrentHashMap<Integer, AtomicLong>
            perPortFrames = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, AtomicLong>
            perPortBytes = new ConcurrentHashMap<>();

    public static long getBulkFramesData() { return bulkFramesData.get(); }
    public static long getBulkBytesData() { return bulkBytesData.get(); }

    public static long getBulkFramesByPort(int portIdx) {
        AtomicLong v = perPortFrames.get(portIdx);
        return v == null ? 0L : v.get();
    }

    public static long getBulkBytesByPort(int portIdx) {
        AtomicLong v = perPortBytes.get(portIdx);
        return v == null ? 0L : v.get();
    }

    public static java.util.SortedMap<Integer, long[]> snapshotPerPort() {
        java.util.TreeMap<Integer, long[]> out = new java.util.TreeMap<>();
        for (var e : perPortFrames.entrySet()) {
            AtomicLong b = perPortBytes.get(e.getKey());
            out.put(e.getKey(), new long[] { e.getValue().get(), b == null ? 0L : b.get() });
        }
        return out;
    }

    public static void resetDataBulkCounters() {
        bulkFramesData.set(0);
        bulkBytesData.set(0);
        perPortFrames.clear();
        perPortBytes.clear();
    }

    private static void onBulkArrived(int portIdx, long payloadLen) {
        bulkFramesData.incrementAndGet();
        bulkBytesData.addAndGet(payloadLen);
        perPortFrames.computeIfAbsent(portIdx, k -> new AtomicLong()).incrementAndGet();
        perPortBytes.computeIfAbsent(portIdx, k -> new AtomicLong()).addAndGet(payloadLen);
    }

    // ===== 实例 =====

    /** 用于把 UDP 事件循环上的 chunk payload 派发到 Minecraft 主线程的注入 seam，避免直接调 MC API。 */
    public interface ChunkDispatcher {
        void dispatch(byte[] compressedPayload);
    }

    /** 缺省 dispatcher：直接调 common {@link ClientChunkHandler}（PoC 行为）。 */
    private static final ChunkDispatcher DEFAULT_DISPATCHER =
            payload -> ClientChunkHandler.handleCompressedChunk(payload);

    private static final long BIND_ACK_TIMEOUT_MS = 2_000L;

    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(2);
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();
    /** 已认证 endpointId → ReliableDatagramSession；仅这些 session 可接收 bulk。 */
    private final ConcurrentHashMap<Integer, ReliableDatagramSession> sessions = new ConcurrentHashMap<>();
    /** 已认证 endpointId → InetSocketAddress remote，回程 dispatch 用。 */
    private final ConcurrentHashMap<Integer, InetSocketAddress> remotes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Channel> workerChannelMap = new ConcurrentHashMap<>();
    private final Object attemptLock = new Object();
    /** 每个 listener group 在任意时刻仅有一个候选正等待认证 ACK。 */
    private final java.util.Map<Integer, PendingAttempt> pendingAttempts = new java.util.HashMap<>();

    private volatile ChunkDispatcher chunkDispatcher = DEFAULT_DISPATCHER;
    private volatile SectionDeltaDispatcher sectionDeltaDispatcher = DEFAULT_SECTION_DELTA_CONSUMER;
    private volatile boolean bound = false;

    /** Task 8 — section delta decoder/handler seam；默认接影子端（submitDelta 任意线程安全）。 */
    public interface SectionDeltaDispatcher {
        void dispatch(io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket packet);
    }

    private static final SectionDeltaDispatcher DEFAULT_SECTION_DELTA_CONSUMER =
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute::submitDelta;

    /** 测试用 seam：替换 chunk 派发器（默认 PoC 主线程调度策略保留）。 */
    public void setChunkDispatcherForTest(ChunkDispatcher d) {
        this.chunkDispatcher = (d == null) ? DEFAULT_DISPATCHER : d;
    }

    /** 测试用 seam：替换 section delta 派发器；null 还原默认。 */
    public void setSectionDeltaDispatcherForTest(SectionDeltaDispatcher d) {
        this.sectionDeltaDispatcher = (d == null) ? DEFAULT_SECTION_DELTA_CONSUMER : d;
    }

    public boolean isBound() { return bound; }

    /** 仅用于同包测试的 seam：把 fake 帧直接灌给 dispatcher，绕开 bind。 */
    void receiveForTest(int type, byte[] payload) {
        safeDispatch(type, payload, /*endpointId*/ 0, null);
    }

    // ----- connectAndBind -----

    /**
     * 为每个 listener group 启动首个 candidate。每个 group 内只允许一个等待认证确认的 UDP socket。
     */
    public void connectAndBind(UUID playerId, long epoch, byte[] token,
                               List<UdpDataPlaneHandshakeTail.UdpListenerGroup> groups) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(token, "token");
        if (token.length != 16) throw new IllegalArgumentException("token must be 16 bytes");
        if (groups == null || groups.isEmpty()) {
            LOGGER.debug("DataPlaneClient: no UDP listener groups advertised; staying Primary-only");
            return;
        }
        Set<Integer> endpointIds = new HashSet<>();
        synchronized (attemptLock) {
            for (UdpDataPlaneHandshakeTail.UdpListenerGroup group : groups) {
                if (!endpointIds.add(group.endpointId())) {
                    throw new IllegalArgumentException("duplicate UDP listener group endpointId=" + group.endpointId());
                }
                startCandidate(playerId, epoch, token, group, 0);
            }
        }
    }

    private void startCandidate(UUID playerId, long epoch, byte[] token,
                                UdpDataPlaneHandshakeTail.UdpListenerGroup group, int candidateIndex) {
        if (candidateIndex >= group.reachableEndpoints().size()) {
            return;
        }
        UdpDataPlaneHandshakeTail.UdpReachableEndpoint candidate = group.reachableEndpoints().get(candidateIndex);
        int endpointId = group.endpointId();
        InetSocketAddress remote = new InetSocketAddress(candidate.host(), candidate.port());
        AtomicReference<Channel> channelRef = new AtomicReference<>();
        ReliableDatagramSession session = new ReliableDatagramSession(playerId, epoch,
                UdpEndpoint.builder().role(UdpEndpoint.Role.CLIENT).localAddress(new InetSocketAddress(0)).build(),
                remote, UdpSessionKey.derive(token, playerId, epoch, endpointId, endpointId),
                datagram -> {
                    Channel channel = channelRef.get();
                    if (channel == null || !channel.isOpen()) {
                        datagram.release();
                        return;
                    }
                    channel.writeAndFlush(new DatagramPacket(datagram, remote));
                }, endpointId, group.weight());
        PendingAttempt attempt = new PendingAttempt(playerId, epoch, token.clone(), group, candidateIndex,
                remote, session, channelRef, System.currentTimeMillis() + BIND_ACK_TIMEOUT_MS);
        session.receiveHandler(received -> safeDispatch(
                received.type(), received.payload(), endpointId, session));

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
                        session.receive(packet.content().retainedDuplicate(), System.currentTimeMillis());
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                        failAttempt(endpointId, session, "UDP receive failure: " + cause);
                    }
                });
        try {
            Channel channel = bootstrap.bind(0).sync().channel();
            channelRef.set(channel);
            channels.add(channel);
            pendingAttempts.put(endpointId, attempt);
            channel.eventLoop().schedule(() -> failAttempt(endpointId, session, "BindAck timeout"),
                    BIND_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            ByteBuf request = encodeBindRequest(token, playerId, epoch, endpointId);
            channel.writeAndFlush(new DatagramPacket(request, remote)).addListener(result -> {
                if (!result.isSuccess()) {
                    failAttempt(endpointId, session, "BindRequest write failed");
                }
            });
            DebugLogger.info(LogType.NETWORK, "[DataPlaneClient] bind candidate eid={} → {}:{}",
                    endpointId, candidate.host(), candidate.port());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeAttempt(attempt);
            throw new IllegalStateException("interrupted while binding UDP client channel", interrupted);
        } catch (Throwable failure) {
            closeAttempt(attempt);
            startCandidate(playerId, epoch, token, group, candidateIndex + 1);
        }
    }

    private void failAttempt(int endpointId, ReliableDatagramSession session, String reason) {
        synchronized (attemptLock) {
            PendingAttempt attempt = pendingAttempts.get(endpointId);
            if (attempt == null || attempt.session() != session) {
                return;
            }
            pendingAttempts.remove(endpointId);
            closeAttempt(attempt);
            DebugLogger.debug(LogType.NETWORK,
                    "DataPlaneClient: candidate failed endpointId={} reason={}", endpointId, reason);
            startCandidate(attempt.playerId(), attempt.epoch(), attempt.token(), attempt.group(),
                    attempt.candidateIndex() + 1);
        }
    }

    private void acknowledgeAttempt(int endpointId, ReliableDatagramSession session, byte[] payload) {
        UdpBindRequestCodec.Ack ack;
        try {
            ack = UdpBindRequestCodec.decodeAck(payload);
        } catch (IllegalArgumentException invalid) {
            failAttempt(endpointId, session, "malformed BindAck");
            return;
        }
        synchronized (attemptLock) {
            PendingAttempt attempt = pendingAttempts.get(endpointId);
            if (attempt == null || attempt.session() != session
                    || ack.endpointId() != endpointId || ack.connectionEpoch() != attempt.epoch()) {
                failAttempt(endpointId, session, "mismatched BindAck");
                return;
            }
            pendingAttempts.remove(endpointId);
            Channel channel = attempt.channelRef().get();
            if (channel == null || !channel.isOpen()) {
                closeAttempt(attempt);
                startCandidate(attempt.playerId(), attempt.epoch(), attempt.token(), attempt.group(),
                        attempt.candidateIndex() + 1);
                return;
            }
            sessions.put(endpointId, session);
            remotes.put(endpointId, attempt.remote());
            workerChannelMap.put(endpointId, channel);
            bound = true;
            LOGGER.info("DataPlaneClient: authenticated UDP bind endpointId={} remote={}", endpointId, attempt.remote());
        }
    }

    private void closeAttempt(PendingAttempt attempt) {
        try { attempt.session().close(); } catch (Throwable ignored) {}
        Channel channel = attempt.channelRef().get();
        if (channel != null) {
            channels.remove(channel);
            try { channel.close(); } catch (Throwable ignored) {}
        }
    }

    private ByteBuf encodeBindRequest(byte[] token, UUID playerId, long epoch, int endpointId) {
        byte[] body = UdpBindRequestCodec.encodeRequest(token, playerId, epoch, endpointId, endpointId);
        ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer(body.length);
        buffer.writeBytes(body);
        return buffer;
    }

    private void safeDispatch(int type, byte[] payload, int endpointId, ReliableDatagramSession session) {
        if (type == DataPlaneFrame.TYPE_BIND_ACK) {
            if (session != null) {
                acknowledgeAttempt(endpointId, session, payload);
            }
        } else if (type == DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK) {
            final byte[] p = payload == null ? new byte[0] : payload;
            try {
                chunkDispatcher.dispatch(p);
            } catch (Throwable t) {
                LOGGER.error("DataPlaneClient: chunk dispatch failed eid={} {}", endpointId, t.toString());
            }
            onBulkArrived(endpointId, p.length);
        } else if (type == DataPlaneFrame.TYPE_BULK_SECTION_DELTA) {
            final byte[] pp = payload == null ? new byte[0] : payload;
            try {
                net.minecraft.network.FriendlyByteBuf fb =
                        new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(pp));
                io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket pkt =
                        io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket.decode(fb);
                sectionDeltaDispatcher.dispatch(pkt);
            } catch (Throwable t) {
                LOGGER.error("DataPlaneClient: section-delta decode/dispatch failed eid={} {}", endpointId, t.toString());
            }
            onBulkArrived(endpointId, pp.length);
        } else if (type == DataPlaneFrame.TYPE_FAILOVER_REQUEST
                || type == DataPlaneFrame.TYPE_FAILOVER_PERMIT) {
            DebugLogger.info(LogType.NETWORK, "[DataPlaneClient] control frame type={} eid={}", type, endpointId);
        }
    }

    /** Lease retention — primary disconnect grace (Task 6/7 control). */
    public void retainLeaseUntil(long deadlineMs) {
        long now = System.currentTimeMillis();
        long leaseMs = Math.max(0L, deadlineMs - now);
        for (var e : sessions.values()) {
            try { e.markLease(now, leaseMs); } catch (Throwable ignored) {}
        }
    }

    /** Tick 已认证会话；ACK timeout 在 UDP event loop 上调度，避免依赖 Minecraft tick。 */
    public void tick(long nowMs) {
        for (ReliableDatagramSession session : sessions.values()) {
            try { session.tick(nowMs); } catch (Throwable ignored) {}
        }
    }

    /** 幂等关闭：pending/已认证 channels + sessions + worker group；不清零全局计数器。 */
    public void shutdown() {
        synchronized (attemptLock) {
            for (PendingAttempt attempt : pendingAttempts.values()) {
                closeAttempt(attempt);
            }
            pendingAttempts.clear();
        }
        for (ReliableDatagramSession session : sessions.values()) {
            try { session.close(); } catch (Throwable ignored) {}
        }
        sessions.clear();
        remotes.clear();
        workerChannelMap.clear();
        for (Channel channel : channels) {
            try { channel.close().await(200, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
        }
        channels.clear();
        try { workerGroup.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS).await(500, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
        bound = false;
    }

    private record PendingAttempt(UUID playerId, long epoch, byte[] token,
                                  UdpDataPlaneHandshakeTail.UdpListenerGroup group, int candidateIndex,
                                  InetSocketAddress remote, ReliableDatagramSession session,
                                  AtomicReference<Channel> channelRef, long deadlineMs) {}

    // ===== helpers =====

    private static String pid(UUID u) { return Long.toHexString(u.getMostSignificantBits()) + Long.toHexString(u.getLeastSignificantBits()); }

    private static byte[] uuidBytes(UUID u) {
        byte[] b = new byte[16];
        long msb = u.getMostSignificantBits(), lsb = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) b[i] = (byte) (msb >>> (56 - 8 * i));
        for (int i = 0; i < 8; i++) b[i + 8] = (byte) (lsb >>> (56 - 8 * i));
        return b;
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
