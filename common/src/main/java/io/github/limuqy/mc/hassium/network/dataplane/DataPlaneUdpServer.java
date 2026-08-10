package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
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
 *       共享一个 {@link NioEventLoopGroup}。bind token 为 per-player per-epoch 16B
 *       （{@link SecureRandom}，{@code ControlFailoverHandler.beginControlConnection} 签发，
 *       epoch 变更即轮换），作为 HKDF per-session 派生根。</li>
 *   <li>每个 datagram 入帧先经「未知 peer / Bind frame 解析」分支：拒绝未知 token / 错配 endpointId；
 *       正确的 (token, playerId, epoch, endpointId, channelId) 派生 per-session key 并构造
 *       {@link ReliableDatagramSession}，注册入 {@link DataPlaneSessionRegistry}。KCP tick 与 lease
 *       过期由 event loop 定期调度。</li>
 *   <li>{@link #tryRouteBulk(UUID, int, byte[])} 委托给 Task 4 router；Task 3 占位实现先返回 false
 *       退化为 Primary-only 行为，Task 4 再覆盖。</li>
 *   <li>{@link #shutdown()}：close 所有 channel + 释放 event loop + 清空 registry。</li>
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
    private static List<HassiumConfig.UdpListenerConfig> testListeners = null;

    public record BoundEndpoint(
            int endpointId,
            int weight,
            int boundPort,
            List<HassiumConfig.ReachableEndpoint> reachableEndpoints
    ) {}

    // ====================== 测试入口 ======================

    /**
     * 测试工厂：注入 immutable listener groups，配合 static {@link #bind()}。返回 handle 仅作 finally 中
     * {@code server.shutdown()} 的句柄——所有实际行为通过 static facade 操作。
     */
    static DataPlaneUdpServer forTest(List<HassiumConfig.UdpListenerConfig> listeners) {
        LOCK.lock();
        try {
            testListeners = List.copyOf(listeners);
            INSTANCE = null;
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
            if (INSTANCE != null) return;
            // 影子端（客户端进程内的 MinecraftServer）不得绑定数据面端口：
            // UDP 数据面只服务专用服务器（dedicated server）场景。
            // 测试模式（forTest 注入 testListeners）不受此 gate 限制。
            if (testListeners == null
                    && !io.github.limuqy.mc.hassium.server.RuntimeServerContext.isDedicatedServerContext()) {
                LOGGER.debug("DataPlaneUdpServer: skip bind (not dedicated server context)");
                return;
            }
            List<HassiumConfig.UdpListenerConfig> listeners = testListeners != null
                    ? testListeners
                    : HassiumConfigService.getInstance().getDataPlaneConfig().udpListeners();
            boolean enabled = testListeners != null
                    || HassiumConfigService.getInstance().getDataPlaneConfig().enabled();
            if (!enabled) {
                LOGGER.info("DataPlaneUdpServer: disabled by dataplane.enabled");
                return;
            }
            Instance inst = new Instance(listeners);
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
            if (inst == null) {
                testListeners = null;
                TEST_INJECTION = null;
                return;
            }
            inst.shutdown();
            INSTANCE = null;
            testListeners = null;
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

    /**
     * D-M1: 返回 (playerId, epoch) 当前下发的 16B bind token（per-player per-epoch，epoch 变更即轮换；
     * 握手响应经 {@link DataPlaneHandshakeAdvertisement} 下发客户端）。未签发或 epoch 已轮换 → null。
     */
    public static byte[] getBindToken(UUID playerId, long epoch) {
        return ControlFailoverHandler.getInstance().bindToken(playerId, epoch);
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
     * 将服务端配置中的 TCP control 可达地址投影为握手尾部记录。
     * bootstrap 地址由客户端从当前 vanilla TCP 连接取得，不能在此处混入。
     */
    public static List<UdpDataPlaneHandshakeTail.ControlEndpoint> advertisedControlEndpoints() {
        return HassiumConfigService.getInstance().getControlReachableEndpoints().stream()
                .map(endpoint -> new UdpDataPlaneHandshakeTail.ControlEndpoint(
                        endpoint.host(), endpoint.port(), endpoint.priority()))
                .toList();
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

    /**
     * 由服务器主时钟推进全部 KCP session 的重传、ACK 与输出，并回收过期 lease。
     * Netty UDP event loop 与本方法可并发：单个 session 自身同步，registry 维护快照。
     */
    public static void tick(long nowMs) {
        Instance inst = INSTANCE;
        if (inst == null) {
            return;
        }
        for (ReliableDatagramSession session : inst.registry.allSessions()) {
            try {
                session.tick(nowMs);
            } catch (Throwable t) {
                LOGGER.warn("DataPlaneUdpServer: session tick failed player={} epoch={}",
                        session.playerId(), session.epoch(), t);
            }
        }
        inst.registry.expireLeases(nowMs);
        sweepIdleSessions(inst, nowMs);
    }

    /**
     * review-fix: T4-M2 — 周期（30s）扫描清理 idle 超时会话（心跳帧不实现，仅超时清理）：
     * <ul>
     *   <li>有 active master（{@link ControlFailoverHandler#currentEpoch} 与该会话 epoch 一致）→
     *       {@link #SESSION_IDLE_TTL_MS}（90s）无 receive 即清理；</li>
     *   <li>无 active master（handler 无该 player 状态或 epoch 不匹配）→ {@link #NO_MASTER_TTL_MS}（30s）短 TTL。</li>
     * </ul>
     * 清理走 {@link DataPlaneSessionRegistry#removeSessions} 统一移除路径并触发
     * {@link ControlFailoverHandler#onUdpSessionClosed} 回调。持续 receive 的 active 会话不会被误清；
     * lease 排干会话由 {@code expireLeases} 先行关闭（lease ≤ 30s < 90s），不受本扫描影响。
     */
    private static void sweepIdleSessions(Instance inst, long nowMs) {
        if (nowMs - inst.lastSweepMs < IDLE_SWEEP_INTERVAL_MS) {
            return;
        }
        inst.lastSweepMs = nowMs;
        ControlFailoverHandler handler = ControlFailoverHandler.getInstance();
        for (ReliableDatagramSession session : inst.registry.allSessions()) {
            long masterEpoch = handler.currentEpoch(session.playerId());
            boolean hasMaster = masterEpoch != 0L && masterEpoch == session.epoch();
            long ttlMs = hasMaster ? SESSION_IDLE_TTL_MS : NO_MASTER_TTL_MS;
            if (nowMs - session.lastActivityMs() >= ttlMs) {
                inst.registry.removeSessions(session.playerId(), session.epoch());
                handler.onUdpSessionClosed(session.playerId(), session.epoch());
                LOGGER.info("UdpServer: idle session swept player={} epoch={} idleMs={} hasMaster={}",
                        session.playerId(), session.epoch(), nowMs - session.lastActivityMs(), hasMaster);
            }
        }
    }

    /** 单例 {@link UdpBulkRouter}；hardRttMs 暂维持协议默认 1000ms。 */
    private static volatile UdpBulkRouter ROUTER;

    private static UdpBulkRouter router() {
        UdpBulkRouter r = ROUTER;
        if (r == null) {
            synchronized (DataPlaneUdpServer.class) {
                r = ROUTER;
                if (r == null) {
                    r = new UdpBulkRouter(1_000L);
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
        UdpBulkRouter.RouteOutcome outcome = router().routeAndPick(ps,
                DataPlanePoCConfig.BULK_ROUTE_MODE.trim().isEmpty() ? "share" : DataPlanePoCConfig.BULK_ROUTE_MODE,
                DataPlanePoCConfig.PRIMARY_WEIGHT,
                DataPlanePoCConfig.DEGRADE_AFTER_DROPS,
                frameType, payload);
        if (outcome.decision() == UdpBulkRouter.RouteDecision.DATA_SENT) {
            int payloadLen = payload == null ? 0 : payload.length;
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordBulkSentData(payloadLen);
            // §14 v2 step 4 重建：router 选中的 BulkRouteTarget 暴露 endpointId（0-based）。
            // NetworkStats.recordBulkSentDataByPort 接口要求 portIdx > 0（1-based），故 +1。
            // chosenOrNull 兜底：DATA_SENT 路径 router 保证非 null，但仍防御 null 避免 metrics 误记。
            BulkRouteTarget chosen = outcome.chosenOrNull();
            if (chosen != null) {
                int portIdxForMetrics = chosen.endpointId() + 1;
                if (portIdxForMetrics > 0) {
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.recordBulkSentDataByPort(portIdxForMetrics, payloadLen);
                }
            }
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
                    long expiryMs = System.currentTimeMillis() + handler.failoverPermitTtlMs(session.playerId());
                    Instance inst = INSTANCE;
                    if (inst != null) {
                        inst.registry.beginFailoverLease(session.playerId(), session.epoch(), expiryMs);
                    }
                    session.enqueueAuthenticated(DataPlaneFrame.TYPE_FAILOVER_PERMIT,
                            FailoverFrameCodec.encodePermit(session.epoch(), expiryMs));
                    LOGGER.info("HassiumSmokeTest:UDP_FAILOVER FAILOVER_PERMIT_OK player={} epoch={} requestedEndpoint={} permitExpiryMs={}",
                            session.playerId(), session.epoch(), request.requestedEndpointId(), expiryMs);
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
    // review-fix: T4-M2 — idle 会话清理：扫描周期 / 有 active master 的 idle TTL / 无 active master 的短 TTL。
    private static final long IDLE_SWEEP_INTERVAL_MS = 30_000L;
    private static final long SESSION_IDLE_TTL_MS = 90_000L;
    private static final long NO_MASTER_TTL_MS = 30_000L;
    private static final int EVENT_LOOP_THREADS = 1;
    private static final byte[] HKDF_INFO_PREFIX = "hassium-udp-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static final class Instance {
        final List<HassiumConfig.UdpListenerConfig> configured;
        final DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
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
        /** review-fix: T4-M2 — 上次 idle 扫描时刻（tick 线程写，tick 线程读）。 */
        long lastSweepMs = 0L;

        Instance(List<HassiumConfig.UdpListenerConfig> listeners) {
            this.configured = List.copyOf(listeners);
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
                for (int endpointId = 0; endpointId < configured.size(); endpointId++) {
                    HassiumConfig.UdpListenerConfig listener = configured.get(endpointId);
                    Bootstrap b = new Bootstrap();
                    b.group(group)
                            .channel(NioDatagramChannel.class)
                            .option(ChannelOption.SO_BROADCAST, false)
                            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                            .handler(new DatagramDispatcher(endpointId, listener.weight()));
                    Channel ch = b.bind(listener.bindHost(), listener.bindPort()).sync().channel();
                    channels.add(ch);
                    InetSocketAddress localAddress = (InetSocketAddress) ch.localAddress();
                    boundEndpoints.add(new BoundEndpoint(
                            endpointId, listener.weight(), localAddress.getPort(), listener.reachableEndpoints()));
                    LOGGER.info("DataPlaneUdpServer: bound UDP {}/{} weight={} endpointId={}",
                            listener.bindHost(), localAddress.getPort(), listener.weight(), endpointId);
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
                ByteBuf buf = packet.content();
                int readable = buf.readableBytes();
                try {

                // 既有 remote 的 KCP 线字节路径：先快速判别是否「看起来像 BindRequest」，
                // 否则转给既有会话处理（KCP 内部去重/重组都会拒绝非 KCP 字节，安全）。
                ReliableDatagramSession existing = byRemote.get(packet.sender());
                if (existing != null && !existing.isClosed()
                        && !(readable >= UdpBindRequestCodec.MIN_BYTES && looksLikeBind(buf,
                                ControlFailoverHandler.getInstance()
                                        .bindToken(existing.playerId(), existing.epoch())))) {
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
                // token 校验（D-M1）：必须等于该 (playerId, epoch) 握手下发的 per-player bind token。
                // epoch 已轮换时 handler 无该 epoch 的 token（null）→ 拒绝——叠加 currentEpoch 校验。
                byte[] expectedToken = ControlFailoverHandler.getInstance()
                        .bindToken(req.playerId(), req.connectionEpoch());
                if (expectedToken == null || !java.util.Arrays.equals(req.token(), expectedToken)) {
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

                byte[] key = UdpSessionKey.derive(expectedToken, req.playerId(), req.connectionEpoch(),
                        endpointId, req.channelId());

                UdpEndpoint ep = UdpEndpoint.builder()
                        .role(UdpEndpoint.Role.SERVER)
                        .localAddress((InetSocketAddress) ctx.channel().localAddress())
                        .build();
                Channel chan = ctx.channel();
                InetSocketAddress peer = packet.sender();
                ReliableDatagramSession.DatagramSink sink = datagram -> {
                    // sink 接管 KCP output 的 duplicate；Netty write 完成后释放。
                    try {
                        chan.writeAndFlush(new DatagramPacket(datagram, peer));
                    } catch (Throwable t) {
                        datagram.release();
                    }
                };
                ReliableDatagramSession session = new ReliableDatagramSession(
                        req.playerId(), req.connectionEpoch(), ep, peer, key, sink, endpointId, weight);
                session.receiveHandler(r -> DataPlaneUdpServer.dispatchReceivedOnServer(session, r));
                if (!session.enqueueAuthenticated(DataPlaneFrame.TYPE_BIND_ACK,
                        UdpBindRequestCodec.encodeAck(req.connectionEpoch(), endpointId))) {
                    session.close();
                    return;
                }
                registry.register(session);
                ControlFailoverHandler.getInstance().onUdpSessionEstablished(req.playerId(), req.connectionEpoch());
                byRemote.put(peer, session);
                LOGGER.info("UdpServer: bound session player={} epoch={} endpoint={} from {}",
                        req.playerId(), req.connectionEpoch(), endpointId, peer);
                LOGGER.info("HassiumSmokeTest:UDP_FAILOVER UDP_BIND_OK player={} epoch={} endpoint={}",
                        req.playerId(), req.connectionEpoch(), endpointId);
                } finally {
                    // 测试同步计数必须代表本帧完整处理完毕；否则 await 会在 register 前返回。
                    dispatchedCount.incrementAndGet();
                }
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
     * 与该既有会话 (playerId, epoch) 的 per-player token 前 4 字节匹配。用于既有 remote 路径上快速区分
     * 「合法 KCP 线字节」与「客户端在已建立会话后还重复发 BindRequest」这两种远方异常。错判只会主动走
     * 解析路径多读一次，不会丢消息——保守即可；token 缺失（epoch 已轮换）按「非 BindRequest」保守处理。
     */
    private static boolean looksLikeBind(ByteBuf buf, byte[] expectedToken) {
        if (buf.readableBytes() < 4 || expectedToken == null || expectedToken.length < 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (buf.getByte(buf.readerIndex() + i) != expectedToken[i]) {
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
