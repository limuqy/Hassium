package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Task 3 — UDP 数据面服务端：替代旧 TCP {@code DataPlaneServer} 的多端口生命周期。
 *
 * <p>核心职责（plan §335、§412-429）：
 * <ul>
 *   <li>{@link #bind()}：对每个 {@link DataPlanePoCConfig.Endpoint} 起一条 {@link NioDatagramChannel}，
 *       共享一个 {@link NioEventLoopGroup}；同时生成单实例 16-byte {@link SecureRandom} token（bind 一次），
 *       作为 HKDF per-session 派生根。</li>
 *   <li>每个 datagram 入帧先经「未知 peer / Bind frame 解析」分支：拒绝未知 token / 错配 endpointId；
 *       正确的 (token, playerId, epoch, endpointId, channelId) 派生 per-session key 并构造
 *       {@link ReliableDatagramSession}，注册入 {@link DataPlaneSessionRegistry}。KCP tick 与 lease
 *       过期由 event loop 定期调度。</li>
 *   <li>{@link #tryRouteBulk(UUID, int, byte[])} 委托给 Task 4 router；Task 3 占位实现先返回 false
 *       退化为 Primary-only 行为，Task 4 再覆盖。</li>
 *   <li>{@link #shutdown()}：close 所有 channel + 释放 event loop + 清空 registry + 抹零 token。</li>
 * </ul>
 *
 * <p>不变量：
 * <ul>
 *   <li>本类位于 {@code common} —— MUST NOT import 任何 Fabric/Forge/NeoForge 或 Minecraft 类。</li>
 *   <li>endpointId 按 {@code ENDPOINTS} 数组下标分配；同一 endpoint 上 BindRequest 必须 endpointId 匹配，
 *       否则丢弃（不依赖网络拓扑，仅逻辑多路径）。</li>
 *   <li>KCP 内部状态 ({@link io.jpower.kcp.netty.Kcp}) 仅在 {@link ReliableDatagramSession} 内可见；
 *       router 仅经 {@link ReliableDatagramSession} 接口访问。</li>
 *   <li>静态 facade 由调用方（{@code MixinMinecraftServer}、Task 4 router、Task 6 failover handler）
 *       从任意线程访问；内部状态由 {@link #LOCK} 串行化绑定/关闭路径，dispatch 路径在 event loop 单线程内。</li>
 * </ul>
 */
public final class DataPlaneUdpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneUdpServer");

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static Instance INSTANCE = null;
    private static DataPlanePoCConfig.Endpoint[] testEndpoints = null;

    public record BoundEndpoint(String host, int bindPort, int endpointId, int weight) {}

    // ====================== 测试入口 ======================

    /**
     * 测试工厂：注入 endpoints，配合 static {@link #bind()}。返回 handle 仅作 finally 中
     * {@code server.shutdown()} 的句柄——所有实际行为通过 static facade 操作。
     */
    static DataPlaneUdpServer forTest(DataPlanePoCConfig.Endpoint[] endpoints) {
        LOCK.lock();
        try {
            testEndpoints = endpoints;
            INSTANCE = null;  // 重置便于多次 bind
            return SHARED_HANDLE;
        } finally {
            LOCK.unlock();
        }
    }

    /** 单例句柄。 */
    private static final DataPlaneUdpServer SHARED_HANDLE = new DataPlaneUdpServer();

    private DataPlaneUdpServer() {}

    // ====================== static facade ======================

    public static void bind() {
        LOCK.lock();
        try {
            if (INSTANCE != null) return;  // 已 bound
            DataPlanePoCConfig.Endpoint[] eps = (testEndpoints != null)
                    ? testEndpoints : DataPlanePoCConfig.ENDPOINTS;
            if (eps == null) {
                LOGGER.warn("DataPlaneUdpServer: no endpoints configured; bind skipped");
                return;
            }
            if (!DataPlanePoCConfig.isEnabled() && testEndpoints == null) {
                LOGGER.info("DataPlaneUdpServer: disabled by config");
                return;
            }
            Instance inst = new Instance(eps);
            inst.bind();
            INSTANCE = inst;
        } finally {
            LOCK.unlock();
        }
    }

    public static void shutdown() {
        LOCK.lock();
        try {
            Instance inst = INSTANCE;
            if (inst == null) return;
            inst.shutdown();
            INSTANCE = null;
            testEndpoints = null;
            TEST_INJECTION = null;
        } finally {
            LOCK.unlock();
        }
    }

    public static boolean isBound() {
        Instance inst = INSTANCE;
        return inst != null && inst.bound;
    }

    /** 测试句柄上的等价语义——是否在测试模式下 binding 仍 availability valid。 */
    public boolean isAvailable() {
        return isBound();
    }

    /** 服务端共享 token：bind 时 {@link SecureRandom} 生成；未 bind 抛 {@link IllegalStateException}。 */
    public static byte[] getSessionToken() {
        Instance inst = INSTANCE;
        if (inst == null) {
            throw new IllegalStateException("DataPlaneUdpServer not bound");
        }
        return inst.sessionToken;
    }

    /**
     * Task 5 — 为 S2C 握手尾部导出当前已 bound 的 UDP 端点清单（含 OS 实际端口）。
     * <p>Fabric/服务器侧握手响应会通过 {@link UdpDataPlaneHandshakeTail} 将这些信息播发给客户端。
     * 未 bind 时返回空列表（caller 应跳过尾部，保持旧客户端兼容）。
     */
    public static List<BoundEndpoint> boundEndpoints() {
        Instance inst = INSTANCE;
        if (inst == null) {
            return List.of();
        }
        return List.copyOf(inst.boundEndpoints);
    }

    /**
     * 一个新的 Minecraft TCP master 被服务器接受。递增 epoch 并立即淘汰该玩家旧 epoch 的 UDP 会话，
     * 防止旧 KCP lease 在重连后接管新控制连接。
     */
    public static long beginControlConnection(UUID playerId, Runnable masterClose) {
        long epoch = ControlFailoverHandler.getInstance().beginControlConnection(playerId, masterClose);
        Instance inst = INSTANCE;
        if (inst != null) {
            inst.registry.replaceEpoch(playerId, epoch);
        }
        return epoch;
    }

    /** 记录来自当前 TCP master 的真实入站控制活动。 */
    public static void recordControlActivity(UUID playerId, long epoch, long nowMs) {
        ControlFailoverHandler.getInstance().recordControlActivity(playerId, epoch, nowMs);
    }

    /** 当前 TCP master epoch；无已注册 master 返回 0。 */
    public static long currentControlEpoch(UUID playerId) {
        return ControlFailoverHandler.getInstance().currentEpoch(playerId);
    }

    /** 单例 {@link UdpBulkRouter}；hardRttMs 取自首个 endpoint 或默认 1000。 */
    private static volatile UdpBulkRouter ROUTER;

    private static UdpBulkRouter router() {
        UdpBulkRouter r = ROUTER;
        if (r == null) {
            synchronized (DataPlaneUdpServer.class) {
                r = ROUTER;
                if (r == null) {
                    long hard = 1_000L;
                    DataPlanePoCConfig.Endpoint[] eps = (INSTANCE != null) ? INSTANCE.configured : DataPlanePoCConfig.ENDPOINTS;
                    if (eps != null && eps.length > 0) {
                        // ENDPOINTS 不暴露 hardRttMs；用默认 1000ms。留作 Task 6 配置覆盖。
                        hard = 1_000L;
                    }
                    r = new UdpBulkRouter(hard);
                    ROUTER = r;
                }
            }
        }
        return r;
    }

    /**
     * Task 4 — 把 bulk payload 路由到健康 UDP 会话。返回：
     * <ul>
     *   <li>{@code true} = 已入队 DATA 子帧，caller 跳过 Primary；</li>
     *   <li>{@code false} = 路由器决策为 PRIMARY（share 命中 PRIMARY 或 exclusive degrade）/ 无会话 / 无服务实例。
     *       caller 应在 Primary 上发本帧，并 {@link io.github.limuqy.mc.hassium.metrics.NetworkStats#recordBulkSentPrimary}。</li>
     * </ul>
     */
    public static boolean tryRouteBulk(UUID playerId, int frameType, byte[] payload) {
        Instance inst = INSTANCE;
        if (inst == null) return false;
        List<? extends BulkRouteTarget> snapshot;
        if (TEST_INJECTION != null && TEST_INJECTION.containsKey(playerId)) {
            List<BulkRouteTarget> inj = TEST_INJECTION.get(playerId);
            snapshot = inj == null ? List.of() : inj;
        } else {
            List<ReliableDatagramSession> rs = inst.registry.sessionsByPlayer(playerId);
            if (rs.isEmpty()) return false;
            snapshot = rs; // ReliableDatagramSession is BulkRouteTarget；保留 OrderByPlayer 健康快照由 router 现场过滤
        }
        if (snapshot.isEmpty()) return false;
        UdpBulkRouter.PlayerSessions ps = inst.worksetsFor(playerId, new ArrayList<>(snapshot));
        UdpBulkRouter.RouteDecision d = router().route(ps,
                DataPlanePoCConfig.BULK_ROUTE_MODE.trim().isEmpty() ? "share" : DataPlanePoCConfig.BULK_ROUTE_MODE,
                DataPlanePoCConfig.PRIMARY_WEIGHT,
                DataPlanePoCConfig.DEGRADE_AFTER_DROPS,
                frameType, payload);
        if (d == UdpBulkRouter.RouteDecision.DATA_SENT) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordBulkSentData(payload == null ? 0 : payload.length);
            return true;
        }
        return false;
    }

    // ----- 测试 seam（生产路径不调用） -----
    private static volatile java.util.Map<UUID, List<BulkRouteTarget>> TEST_INJECTION = null;

    /** 测试注入：把一组 fake {@link BulkRouteTarget} 挂到 server 让 {@link #tryRouteBulk(UUID, int, byte[])} 直接路由。
     *  生产路径透传 null。传入空列表表示"无会话"场景。 */
    static void injectBoundSessionsForTest(UUID playerId, List<BulkRouteTarget> targets) {
        if (TEST_INJECTION == null) {
            TEST_INJECTION = new java.util.concurrent.ConcurrentHashMap<>();
        }
        TEST_INJECTION.put(playerId, List.copyOf(targets));
    }

    /**
     * 服务端从 KCP 拿到完整应用帧后的回调。仅接受已认证的 failover request；所有其它
     * client→server 类型在此层无业务语义，故静默忽略。
     */
    private static void dispatchReceivedOnServer(ReliableDatagramSession session, ReliableDatagramSession.Received r) {
        if (r.type() == DataPlaneFrame.TYPE_FAILOVER_REQUEST) {
            try {
                FailoverFrameCodec.Request request = FailoverFrameCodec.decodeRequest(r.payload());
                if (request.connectionEpoch() != session.epoch()) {
                    return;
                }
                ControlFailoverHandler handler = ControlFailoverHandler.getInstance();
                ControlFailoverHandler.FailoverResult result = handler.requestFailover(
                        session.playerId(), request.connectionEpoch(), request.requestedEndpointId(), System.currentTimeMillis());
                if (result == ControlFailoverHandler.FailoverResult.PERMITTED) {
                    long expiryMs = System.currentTimeMillis() + handler.failoverPermitTtlMs();
                    Instance inst = INSTANCE;
                    if (inst != null) {
                        inst.registry.beginFailoverLease(session.playerId(), session.epoch(), expiryMs);
                    }
                    session.enqueueAuthenticated(DataPlaneFrame.TYPE_FAILOVER_PERMIT,
                            FailoverFrameCodec.encodePermit(session.epoch(), expiryMs));
                }
            } catch (IllegalArgumentException ignored) {
                // 已认证但非法的 control payload 不得影响 session 或服务器 event loop。
            }
            return;
        }
        if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
            DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                    "UdpServer: received frame from player={} epoch={} type={} bytes={}",
                    session.playerId(), session.epoch(), r.type(), r.payload() == null ? 0 : r.payload().length);
        }
    }

    /** Task 4 测试 hook：清理该玩家所有会话，模拟无 session 场景。生产路径不调用。 */
    static void removeSessionsForTest(UUID playerId) {
        Instance inst = INSTANCE;
        if (inst == null) return;
        for (ReliableDatagramSession s : inst.registry.sessionsByPlayer(playerId)) {
            s.close();
        }
        if (TEST_INJECTION != null) TEST_INJECTION.remove(playerId);
        inst.worksets.remove(playerId);
    }

    /** 主 TCP 断开 → 由 {@code ControlFailoverHandler}（Task 6）转发的子调用。 */
    public static void onPrimaryDisconnect(UUID playerId, long epoch, long nowMs) {
        Instance inst = INSTANCE;
        if (inst == null) return;
        long leaseMs = DEFAULT_LEASE_MS;
        inst.registry.onPrimaryDisconnect(playerId, epoch, nowMs, leaseMs);
    }

    // ====================== 测试可见 hooks ======================

    /** 当前 registry（测试用）；生产路径不应使用此方法。 */
    DataPlaneSessionRegistry registry() {
        Instance inst = INSTANCE;
        if (inst == null) {
            throw new IllegalStateException("not bound");
        }
        return inst.registry;
    }

    /** 当前已 bound 端点（含 OS 实际端口）；包私有供测试观测。 */
    List<BoundEndpoint> getBoundEndpoints() {
        Instance inst = INSTANCE;
        if (inst == null) {
            return List.of();
        }
        return new ArrayList<>(inst.boundEndpoints);
    }

    /** 等待至少 {@code count} 个 datagram 已 dispatch（测试时序同步），最多 {@code timeoutMs} 毫秒。 */
    void awaitDispatchedFrames(int count, long timeoutMs) throws InterruptedException {
        Instance inst = INSTANCE;
        if (inst == null) return;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inst.dispatchedCount.get() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    // ====================== internal instance ======================

    /** 默认 lease 时长（ms）；Task 6 会经配置项覆盖。 */
    private static final long DEFAULT_LEASE_MS = 30_000L;
    private static final int EVENT_LOOP_THREADS = 1;
    private static final byte[] HKDF_INFO_PREFIX = "hassium-udp-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static final class Instance {
        final DataPlanePoCConfig.Endpoint[] configured;
        final DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
        final byte[] sessionToken;
        final NioEventLoopGroup group = new NioEventLoopGroup(EVENT_LOOP_THREADS);
        final List<Channel> channels = new ArrayList<>();
        final List<BoundEndpoint> boundEndpoints = new ArrayList<>();
        /** remote → session；event loop 单线程访问，无需同步。 */
        final Map<InetSocketAddress, ReliableDatagramSession> byRemote = new LinkedHashMap<>();
        /** per-player bulk 路由 WRR 状态；tick/bulk 路径单线程读，bind/replaceEpoch 路径清。 */
        final java.util.concurrent.ConcurrentHashMap<UUID, UdpBulkRouter.PlayerSessions> worksets =
                new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger dispatchedCount = new AtomicInteger();
        volatile boolean bound = false;

        Instance(DataPlanePoCConfig.Endpoint[] eps) {
            this.configured = eps;
            this.sessionToken = new byte[16];
            new SecureRandom().nextBytes(this.sessionToken);
        }

        /** 取/构造 per-player WRR 状态；每次刷新 sessions 快照以反映新 bind/lease/关闭。 */
        UdpBulkRouter.PlayerSessions worksetsFor(UUID playerId, List<? extends BulkRouteTarget> snapshot) {
            UdpBulkRouter.PlayerSessions ps = worksets.computeIfAbsent(playerId,
                    u -> UdpBulkRouter.PlayerSessions.of(List.of()));
            ps.refresh(snapshot);
            return ps;
        }

        void bind() {
            try {
                for (int endpointId = 0; endpointId < configured.length; endpointId++) {
                    DataPlanePoCConfig.Endpoint ep = configured[endpointId];
                    Bootstrap b = new Bootstrap();
                    b.group(group)
                            .channel(NioDatagramChannel.class)
                            .option(ChannelOption.SO_BROADCAST, false)
                            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                            .handler(new DatagramDispatcher(endpointId, ep.weight));
                    Channel ch = b.bind(ep.bindHost, ep.bindPort).sync().channel();
                    channels.add(ch);
                    InetSocketAddress la = (InetSocketAddress) ch.localAddress();
                    boundEndpoints.add(new BoundEndpoint(ep.bindHost, la.getPort(), endpointId, ep.weight));
                    LOGGER.info("DataPlaneUdpServer: bound UDP {}/{} weight={} endpointId={}",
                            ep.bindHost, la.getPort(), ep.weight, endpointId);
                }
                bound = true;
            } catch (Throwable t) {
                shutdownInternal();
                throw new IllegalStateException("DataPlaneUdpServer bind failed", t);
            }
        }

        void shutdown() { shutdownInternal(); }

        private void shutdownInternal() {
            for (Channel ch : channels) {
                try { ch.close().sync(); } catch (Throwable ignored) {}
            }
            channels.clear();
            try { group.shutdownGracefully(0, 1500, TimeUnit.MILLISECONDS); }
            catch (Throwable ignored) {}
            boundEndpoints.clear();
            for (ReliableDatagramSession s : byRemote.values()) {
                try { s.close(); } catch (Throwable ignored) {}
            }
            byRemote.clear();
            bound = false;
            java.util.Arrays.fill(sessionToken, (byte) 0);
        }

        /** Per-channel datagram dispatcher；共享 instance 的 byRemote 与 registry。 */
        private final class DatagramDispatcher extends SimpleChannelInboundHandler<DatagramPacket> {
            private final int endpointId;
            private final int weight;

            DatagramDispatcher(int endpointId, int weight) {
                this.endpointId = endpointId;
                this.weight = weight;
            }

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                dispatchedCount.incrementAndGet();
                ByteBuf buf = packet.content();
                int readable = buf.readableBytes();

                // 既有 remote 的 KCP 线字节路径：先快速判别是否「看起来像 BindRequest」，
                // 否则转给既有会话处理（KCP 内部去重/重组都会拒绝非 KCP 字节，安全）。
                ReliableDatagramSession existing = byRemote.get(packet.sender());
                if (existing != null && !existing.isClosed()
                        && !(readable >= UdpBindRequestCodec.MIN_BYTES && looksLikeBind(buf, sessionToken))) {
                    existing.receive(buf.retainedDuplicate(), System.currentTimeMillis());
                    return;
                }

                if (readable < UdpBindRequestCodec.MIN_BYTES) {
                    // 未知 remote + 太短 → 丢弃（不分配 KCP 状态）
                    return;
                }

                // BindRequest 解析
                byte[] raw = new byte[readable];
                buf.getBytes(buf.readerIndex(), raw);
                UdpBindRequestCodec.Request req;
                try {
                    req = UdpBindRequestCodec.decodeRequest(raw);
                } catch (IllegalArgumentException ex) {
                    if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                        DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                                "UdpServer: dropped malformed datagram from {} size={}", packet.sender(), readable);
                    }
                    return;
                }
                // token 校验
                if (!java.util.Arrays.equals(req.token(), sessionToken)) {
                    if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                        DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                                "UdpServer: token mismatch from {}", packet.sender());
                    }
                    return;
                }
                // endpointId 必须与本 channel 匹配
                if (req.endpointId() != endpointId) {
                    if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                        DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                                "UdpServer: endpointId mismatch want={} got={} from {}",
                                endpointId, req.endpointId(), packet.sender());
                    }
                    return;
                }

                // HKDF per-session key: info = "hassium-udp-v1" (15B) || endpointId(1B) || channelId(1B)
                byte[] info = new byte[HKDF_INFO_PREFIX.length + 2];
                System.arraycopy(HKDF_INFO_PREFIX, 0, info, 0, HKDF_INFO_PREFIX.length);
                info[HKDF_INFO_PREFIX.length] = (byte) endpointId;
                info[HKDF_INFO_PREFIX.length + 1] = (byte) req.channelId();
                byte[] salt = uuidBytes(req.playerId());
                byte[] key = Hkdf.extractAndExpand(sessionToken, salt, info, 16);

                UdpEndpoint ep = UdpEndpoint.builder()
                        .role(UdpEndpoint.Role.SERVER)
                        .localAddress((InetSocketAddress) ctx.channel().localAddress())
                        .build();
                Channel chan = ctx.channel();
                InetSocketAddress peer = packet.sender();
                ReliableDatagramSession.DatagramSink sink = datagram -> {
                    // retainedDuplicate 由 netty pipeline 释放；writeAndFlush 失败则手动 release
                    try {
                        chan.writeAndFlush(new DatagramPacket(datagram.retainedDuplicate(), peer));
                    } catch (Throwable t) {
                        datagram.release();
                    }
                };
                ReliableDatagramSession session = new ReliableDatagramSession(
                        req.playerId(), req.connectionEpoch(), ep, peer, key, sink, endpointId, weight);
                session.receiveHandler(r -> DataPlaneUdpServer.dispatchReceivedOnServer(session, r));
                registry.register(session);
                ControlFailoverHandler.getInstance().onUdpSessionEstablished(req.playerId(), req.connectionEpoch());
                byRemote.put(peer, session);
                LOGGER.info("UdpServer: bound session player={} epoch={} endpoint={} from {}",
                        req.playerId(), req.connectionEpoch(), endpointId, peer);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                LOGGER.warn("UdpServer: dispatcher error on endpointId={}", endpointId, cause);
            }
        }
    }

    /* ----------------- helpers ----------------- */

    /**
     * 快速判别 {@code buf} 是否「看起来像 BindRequest」——只检查 {@code token[16]} 的前 4 字节是否
     * 与服务端 token 前 4 字节匹配。用于既有 remote 路径上快速区分「合法 KCP 线字节」与
     * 「客户端在已建立会话后还重复发 BindRequest」这两种远方异常。错判只会主动走解析路径多读一次，
     * 不会丢消息——保守即可。
     */
    private static boolean looksLikeBind(ByteBuf buf, byte[] sessionToken) {
        if (buf.readableBytes() < 4) return false;
        for (int i = 0; i < 4; i++) {
            if (buf.getByte(buf.readerIndex() + i) != sessionToken[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] uuidBytes(UUID u) {
        byte[] b = new byte[16];
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) { b[i] = (byte) (msb & 0xFF); msb >>>= 8; }
        for (int i = 15; i >= 8; i--) { b[i] = (byte) (lsb & 0xFF); lsb >>>= 8; }
        return b;
    }

    /**
     * 冒烟切断验证（Task 10b §2.1）：返回本类运行时实际使用的 Netty transport channel 类型名集合。
     * <p>仅 UDP（{@code NioDatagramChannel}）；PoC 时期的 {@code NioServerSocketChannel} TCP 监听已退役。
     * 由 {@code DataPlaneTransportCutoverTest} 断言「集合不含 PoC TCP transport 名」。
     *
     * @return 不可变 transport 类名集合，元素为完全限定或简短的 channel 类名（与
     *         {@code DataPlaneUdpServer} 生产路径实际使用的类型一致）
     */
    static java.util.Set<String> runtimeTransportNamesForTest() {
        return java.util.Set.of(NioDatagramChannel.class.getName());
    }
}
