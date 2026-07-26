package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/**
 * Task 5 — 客户端 UDP 数据面 bundle（替换 PoC TCP {@code DataPlaneClientBundle}）。
 *
 * <p><b>职责不变量</b>：每条 advertised server {@link UdpDataPlaneHandshakeTail.UdpEndpointInfo}
 * 对应一个 {@link ReliableDatagramSession}（{@code Role.CLIENT}），先发绑定 BindRequest 走 AES-GCM
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
    public static volatile long bulkFramesData = 0;
    public static volatile long bulkBytesData = 0;
    private static final ConcurrentHashMap<Integer, AtomicLong>
            perPortFrames = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, AtomicLong>
            perPortBytes = new ConcurrentHashMap<>();

    public static long getBulkFramesData() { return bulkFramesData; }
    public static long getBulkBytesData() { return bulkBytesData; }

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
        bulkFramesData = 0;
        bulkBytesData = 0;
        perPortFrames.clear();
        perPortBytes.clear();
    }

    private static void onBulkArrived(int portIdx, long payloadLen) {
        bulkFramesData++;
        bulkBytesData += payloadLen;
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

    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(2);
    private final List<Channel> channels = new ArrayList<>();
    /** endpointId → ReliableDatagramSession，单线程只读访问由 worker 守。 */
    private final ConcurrentHashMap<Integer, ReliableDatagramSession> sessions = new ConcurrentHashMap<>();
    /** endpointId → InetSocketAddress remote，回程 dispatch 用。 */
    private final ConcurrentHashMap<Integer, InetSocketAddress> remotes = new ConcurrentHashMap<>();

    private volatile ChunkDispatcher chunkDispatcher = DEFAULT_DISPATCHER;
    private volatile boolean bound = false;

    /** 测试用 seam：替换 chunk 派发器（默认 PoC 主线程调度策略保留）。 */
    public void setChunkDispatcherForTest(ChunkDispatcher d) {
        this.chunkDispatcher = (d == null) ? DEFAULT_DISPATCHER : d;
    }

    public boolean isBound() { return bound; }

    /** 仅用于同包测试的 seam：把 fake 帧直接灌给 dispatcher，绕开 bind。 */
    void receiveForTest(int type, byte[] payload) {
        safeDispatch(type, payload, /*endpointId*/ 0);
    }

    // ----- connectAndBind -----

    /** Task 5 main entry — 连接所有 advertised server UDP endpoints 并发 BindRequest。 */
    public void connectAndBind(UUID playerId, long epoch, byte[] token,
                              List<UdpDataPlaneHandshakeTail.UdpEndpointInfo> endpoints) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(token, "token");
        if (token.length != 16) throw new IllegalArgumentException("token must be 16 bytes");
        if (endpoints == null || endpoints.isEmpty()) {
            LOGGER.debug("DataPlaneClient: no UDP endpoints advertised; staying Primary-only");
            bound = false;
            return;
        }
        String pid = pid(playerId);
        for (UdpDataPlaneHandshakeTail.UdpEndpointInfo e : endpoints) {
            try {
                bindOneEndpoint(pid, playerId, epoch, token, e);
            } catch (Throwable t) {
                LOGGER.error("DataPlaneClient: bind failed endpointId={} {}: {}", e.endpointId(), e.host() + ":" + e.port(), t.toString());
            }
        }
        bound = !sessions.isEmpty();
    }

    private void bindOneEndpoint(String pid, UUID playerId, long epoch, byte[] token,
                                 UdpDataPlaneHandshakeTail.UdpEndpointInfo info) {
        InetSocketAddress remote = new InetSocketAddress(info.host(), info.port());
        // key derived exactly the same wire contract as DataPlaneUdpServer:
        // HKDF(token, uuidBytes(playerId) || epochBytes, info="hassium-udp-v1" || endpointId || channelId, 16)
        byte[] key = Hkdf.extractAndExpand(
                token,
                concat(uuidBytes(playerId), longBytes(epoch)),
                concat("hassium-udp-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        new byte[] { (byte) info.endpointId(), (byte) (info.endpointId() & 0xFF) }),
                16);

        UdpEndpoint ep = UdpEndpoint.builder()
                .role(UdpEndpoint.Role.CLIENT)
                .localAddress(new InetSocketAddress(0))
                .build();
        ReliableDatagramSession sess = new ReliableDatagramSession(playerId, epoch, ep, remote, key,
                new ReliableDatagramSession.DatagramSink() {
                    @Override
                    public void send(ByteBuf datagram) {
                        Channel ch = workerChannelForEndpoint(info.endpointId());
                        if (ch == null || !ch.isOpen()) {
                            datagram.release();
                            return;
                        }
                        ch.writeAndFlush(new DatagramPacket(datagram, remote))
                          .addListener(f -> {
                              if (!f.isSuccess() && DebugLogger.isEnabled(LogType.NETWORK)) {
                                  DebugLogger.warn(LogType.NETWORK,
                                          "[DataPlaneC] send failed eid={} {}", info.endpointId(), f.cause().toString());
                              }
                          });
                    }
                });
        sess.receiveHandler(r -> safeDispatch(r.type(), r.payload(), info.endpointId()));

        // Open a disconnected UDP datagram channel owned by this bundle.
        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
         .channel(NioDatagramChannel.class)
         .option(ChannelOption.SO_BROADCAST, false)
         .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        Channel ch;
        try {
            ch = b.bind(0).sync().channel();
            channels.add(ch);
            workerChannelMap.put(info.endpointId(), ch);
            sessions.put(info.endpointId(), sess);
            remotes.put(info.endpointId(), remote);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while binding UDP client channel", ie);
        }

        // Send authenticated BindRequest.
        try {
            ByteBuf req = encodeBindRequest(token, playerId, epoch, info.endpointId());
            ch.writeAndFlush(new DatagramPacket(req, remote));
        } catch (Throwable t) {
            LOGGER.error("DataPlaneClient: bind send failed eid={} {}", info.endpointId(), t.toString());
        }
        DebugLogger.info(LogType.NETWORK, "[DataPlaneClient] bind fired eid={} → {}:{}",
                info.endpointId(), info.host(), info.port());
    }

    private final ConcurrentHashMap<Integer, Channel> workerChannelMap = new ConcurrentHashMap<>();

    private Channel workerChannelForEndpoint(int endpointId) {
        return workerChannelMap.get(endpointId);
    }

    private ByteBuf encodeBindRequest(byte[] token, UUID playerId, long epoch, int endpointId) {
        byte[] body = UdpBindRequestCodec.encodeRequest(token, playerId, epoch, endpointId, /*channelId=*/ endpointId);
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(body.length);
        buf.writeBytes(body);
        return buf;
    }

    private void safeDispatch(int type, byte[] payload, int endpointId) {
        if (type == DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK) {
            final byte[] p = payload == null ? new byte[0] : payload;
            try {
                chunkDispatcher.dispatch(p);
            } catch (Throwable t) {
                LOGGER.error("DataPlaneClient: chunk dispatch failed eid={} {}", endpointId, t.toString());
            }
            onBulkArrived(endpointId, p.length);
        } else if (type == DataPlaneFrame.TYPE_FAILOVER_REQUEST
                || type == DataPlaneFrame.TYPE_FAILOVER_PERMIT) {
            // Task 6/9 owns these; client handler hooks in HassiumClientMod.
            DebugLogger.info(LogType.NETWORK, "[DataPlaneClient] control frame type={} eid={}", type, endpointId);
        } else if (type == DataPlaneFrame.TYPE_KEEPALIVE) {
            // PoC retained keepalive acknowledgement per session; no-op here.
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

    /** Tick all sessions. */
    public void tick(long nowMs) {
        for (var e : sessions.values()) {
            try { e.tick(nowMs); } catch (Throwable ignored) {}
        }
    }

    /** 幂等关闭：channels + sessions + worker group；不清零全局计数器（caller 显式 reset）。 */
    public void shutdown() {
        for (ReliableDatagramSession s : sessions.values()) {
            try { s.close(); } catch (Throwable ignored) {}
        }
        sessions.clear();
        remotes.clear();
        workerChannelMap.clear();
        for (Channel ch : channels) {
            try { ch.close().await(200, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
        }
        channels.clear();
        try { workerGroup.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS).await(500, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
        bound = false;
    }

    // ----- channel dispatcher -----

    private final class ClientDatagramDispatcher extends SimpleChannelInboundHandler<DatagramPacket> {
        private final int endpointId;
        ClientDatagramDispatcher(int eid) { this.endpointId = eid; }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            ReliableDatagramSession sess = sessions.get(endpointId);
            if (sess == null) return;
            ByteBuf buf = packet.content();
            try {
                sess.receive(buf.retainedDuplicate(), System.currentTimeMillis());
            } catch (Throwable t) {
                LOGGER.error("DataPlaneClient: receive failed eid={} {}", endpointId, t.toString());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.error("DataPlaneClient: channel exception eid={} {}", endpointId, cause.toString());
        }
    }

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
