package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationEndpoint;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationEngine;
import io.github.limuqy.mc.hassium.network.core.migration.PrewarmSession;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.OutboundConnection;
import io.github.limuqy.mc.hassium.network.core.outbound.UdpDataPlane;
import io.github.limuqy.mc.hassium.network.core.viafabric.ViaFabricCompat;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 网络核心（进程内网关）单例：完全接管客户端↔主控的网络收发（REQ A 节）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>状态机</b>：{@link NetworkCoreState}（IDLE/CONNECTING/HANDSHAKING/ACTIVE/MIGRATING），
 *       原子转移 + 日志；客户端原版 Connection 状态零变化。</li>
 *   <li><b>outbound 自有通道</b>：{@link OutboundConnection}（TCP 控制面帧协议）+ {@link UdpDataPlane}
 *       （bulk 区块 UDP 数据面）；握手编解码 {@link HandshakeCodec}（三端内联格式提取）。</li>
 *   <li><b>入站契约</b>：{@link #dispatchS2C} 注入器注册口（T5 实现体：handler 层直调原版
 *       {@code ClientPacketListener.handleXxx}）；计数 {@link #s2cDispatchedCount} 可验证。</li>
 *   <li><b>出站契约</b>：{@link #routeC2S} world 侧出站收口（T5 接入 MixinConnection 截获），
 *       经 {@link C2SEncoder} 编码进 outbound；计数 {@link #c2sRoutedCount} 可验证。</li>
 * </ul>
 *
 * <p>生命周期：{@link ClientLifecycleHelper#onLogin()} / {@code cleanupOnDisconnect()} 接线
 * （最小侵入，各一行）。
 *
 * <p>线程模型：onLogin/onDisconnect/routeC2S 主线程；onOpen/onHandshakeAccepted/onError
 * Netty event loop 线程。状态为原子引用，契约容器为 COW/原子，线程安全。
 */
public final class NetworkCore implements OutboundConnection.Listener, MigrationEngine.Sink {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NetworkCore");

    private static final NetworkCore INSTANCE = new NetworkCore();

    public static NetworkCore getInstance() {
        return INSTANCE;
    }

    private final AtomicReference<NetworkCoreState> state = new AtomicReference<>(NetworkCoreState.IDLE);
    private final AtomicLong s2cDispatched = new AtomicLong();
    private final AtomicLong c2sRouted = new AtomicLong();
    private final AtomicLong loginRelayed = new AtomicLong();
    private final AtomicLong configRelayed = new AtomicLong();
    private final CopyOnWriteArrayList<Consumer<Packet<?>>> s2cInjectors = new CopyOnWriteArrayList<>();
    private final AtomicReference<C2SEncoder> c2sEncoder = new AtomicReference<>();
    private final AtomicReference<Function<Packet<?>, Packet<?>>> s2cTranslator = new AtomicReference<>();

    private volatile OutboundConnection outbound;
    private volatile HandshakeCodec.ServerResponse lastHandshake;
    private volatile String lastEndpoint;
    private volatile boolean lastResumeAccepted;
    private volatile net.minecraft.network.Connection vanillaConnection;

    /** L1 迁移引擎（REQ C 节）：触发判定 + 迁移编排 + 预热 + 空闲窗口。 */
    private final MigrationEngine migration = new MigrationEngine();

    /** 迁移中目标端点（故障/回调决策用）。 */
    private volatile MigrationEndpoint pendingMigrationTarget;
    /** 切换动作已落地（预热回调防重入；迁移成功/回退时复位）。 */
    private volatile boolean migrationResolved;
    /** 迁移连接尝试计数（成功/新会话复位）。 */
    private final AtomicLong migrationAttempts = new AtomicLong();

    private static final long MAX_MIGRATION_ATTEMPTS = 3;

    private NetworkCore() {
        // T5：S2C 注入器（handler 层直调路由）随单例就位；outbound payload 缝在 attach 时接线
        registerS2CInjector(GatewayS2CRouter.INSTANCE);
        // T10：C2S 编码器真实接线——PLAY SERVERBOUND 原版包 → 控制帧 payload（routeC2S 收口）。
        // 未知/自定义包 encode 抛异常由 routeC2S 兜底（返回 false → 原版放行），不吞包。
        c2sEncoder.set(packet -> GatewayPacketCodec.encodeVanilla(
                packet, PacketFlow.SERVERBOUND, GatewayPacketCodec.GatewayProtocol.PLAY,
                resolveClientRegistryAccess()));
        // T8：迁移引擎接线——故障/策略 sink = 本核心；玩家身份/位置源 = 客户端会话；
        // 故障静默超时沿用 recoveryWindow 语义（原 recoveryWindowMs 键，现为 master.migrationFaultTimeoutMs；配置值覆盖默认）
        migration.setSink(this);
        migration.setPlayerStateSource(NetworkCore::clientPlayerState);
        migration.setPlayerIdSource(NetworkCore::clientPlayerId);
        try {
            long migrationFaultTimeoutMs = HassiumConfigService.getInstance()
                    .getMigrationFaultTimeoutMs();
            migration.applyMigrationFaultTimeoutFromConfig(migrationFaultTimeoutMs);
        } catch (Throwable t) {
            LOGGER.debug("Hassium: migration fault-timeout config read skipped", t);
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 玩家登录（vanilla login 完成后，主线程）：关闭陈旧 outbound、清零计数、进入 CONNECTING，
     * 并按当前连接地址尝试自动建立 outbound（失败仅告警，正式地址源为 T7 迁移引擎）。
     */
    public void onLogin() {
        OutboundConnection stale = outbound;
        if (stale != null) {
            stale.close();
            outbound = null;
        }
        s2cDispatched.set(0);
        c2sRouted.set(0);
        loginRelayed.set(0);
        configRelayed.set(0);
        lastHandshake = null;
        lastResumeAccepted = false;
        migrationAttempts.set(0);
        // T9：ViaFabric 兼容——新会话重探测 + 重置转换桥（旧 UserConnection 失效）并接线翻译器
        ViaFabricCompat.INSTANCE.onLogin();
        // T8：迁移引擎心跳监测随会话启动（守护线程；测试不经过此路径）
        migration.start();
        transitionTo(NetworkCoreState.CONNECTING);
        LOGGER.info("Hassium: NetworkCore onLogin -> CONNECTING");
        autoConnect();
    }

    /** 玩家断开（ClientLifecycleHelper.cleanupOnDisconnect，世界拆除前）：关 outbound → IDLE。 */
    public void onDisconnect() {
        OutboundConnection oc = outbound;
        outbound = null;
        if (oc != null) {
            oc.close();
        }
        lastHandshake = null;
        lastResumeAccepted = false;
        vanillaConnection = null;
        // T8：停心跳监测 + 清预热（幂等）
        migration.stop();
        // 连接周期计数：断连即周期结束（登录/路由计数按 onLogin 累计的语义对称）
        s2cDispatched.set(0);
        c2sRouted.set(0);
        loginRelayed.set(0);
        configRelayed.set(0);
        transitionTo(NetworkCoreState.IDLE);
        LOGGER.info("Hassium: NetworkCore onDisconnect -> IDLE");
    }

    /**
     * 建立到主控的 outbound 连接（异步）。IDLE 时先转 CONNECTING；
     * ACTIVE/MIGRATING 中调用 = 重连/切换（旧 outbound 关闭）。
     * 握手自动携带 T10 标准流程尾（玩家 UUID/位置，主控据此把会话附着到 vanilla 物化玩家）。
     */
    public void connect(String host, int port) {
        connect(host, port, buildAutoTail());
    }

    /**
     * 建立到主控的 outbound 连接（异步）。IDLE 时先转 CONNECTING；
     * ACTIVE/MIGRATING 中调用 = 重连/切换（旧 outbound 关闭）。
     * tail 非 null 时握手携带 T7/T10 状态尾（T8 迁移续流发起 / T10 标准流程身份附着）。
     */
    public void connect(String host, int port, HandshakeStateTail.C2S tail) {
        if (state.get() == NetworkCoreState.IDLE) {
            transition(NetworkCoreState.IDLE, NetworkCoreState.CONNECTING);
        }
        lastEndpoint = host + ":" + port;
        migration.noteCurrentEndpoint(new MigrationEndpoint(host, port));
        attach(createOutbound(host, port, tail));
        LOGGER.info("Hassium: NetworkCore connecting outbound to {}", lastEndpoint);
    }

    /** 经迁移引擎连接工厂创建 outbound（默认真实 NIO；测试注入 EmbeddedChannel 缝）。 */
    private OutboundConnection createOutbound(String host, int port, HandshakeStateTail.C2S tail) {
        return migration.createConnection(host, port,
                HandshakeCodec.ClientRequestOptions.defaults(), tail, this);
    }

    /**
     * L1 主控切换入口（直接切换，T4 既有语义）：ACTIVE → MIGRATING → 关闭旧 outbound →
     * 连接新主控（续流票据握手）。原版 Connection 全程不动。
     */
    public void migrate(String host, int port) {
        migrateToImmediate(new MigrationEndpoint(host, port));
    }

    /**
     * L1 迁移入口（预热感知，REQ C 节）：ACTIVE → MIGRATING。预热启用时先建目标主控
     * 玩家会话（位置上报 + resyncTrackedChunks，T12 服务端已落地），就绪后关旧 outbound
     * 并接管预热连接（重叠切换：旧连接服务至预热就绪）；预热失败/禁用 → 直接续流连接。
     */
    public void migrateTo(MigrationEndpoint endpoint) {
        if (!transition(NetworkCoreState.ACTIVE, NetworkCoreState.MIGRATING)) {
            LOGGER.warn("Hassium: migrateTo rejected (state={}, MIGRATING 需从 ACTIVE 进入)", state.get());
            return;
        }
        migration.noteMigrationStarted();
        migration.noteCurrentEndpoint(endpoint);
        pendingMigrationTarget = endpoint;
        migrationResolved = false;
        lastEndpoint = endpoint.host() + ":" + endpoint.port();
        LOGGER.info("Hassium: NetworkCore migrating to {} (prewarm={}, idleWindow={})",
                endpoint, migration.policy().prewarmEnabled(), migration.isIdleWindow());
        if (!migration.policy().prewarmEnabled()) {
            closeOldOutbound();
            connectWithResume(endpoint);
            return;
        }
        PrewarmSession warm = migration.takePrewarm(endpoint);
        if (warm != null) {
            promotePrewarm(warm);
            return;
        }
        if (migration.prewarmInFlight(endpoint)) {
            LOGGER.info("Hassium: migrateTo awaiting in-flight prewarm session to {}", endpoint);
            return;
        }
        migration.prewarm(endpoint, prewarmCallback);
    }

    /**
     * 迁移入口（立即切换，故障路径）：ACTIVE → MIGRATING → 关闭旧 outbound →
     * 连接新主控（续流票据握手）。不预热（故障时目标会话由续流握手直接建立）。
     */
    public void migrateToImmediate(MigrationEndpoint endpoint) {
        if (!transition(NetworkCoreState.ACTIVE, NetworkCoreState.MIGRATING)) {
            LOGGER.warn("Hassium: migrateToImmediate rejected (state={}, MIGRATING 需从 ACTIVE 进入)",
                    state.get());
            return;
        }
        migration.noteMigrationStarted();
        migration.noteCurrentEndpoint(endpoint);
        pendingMigrationTarget = endpoint;
        migrationResolved = false;
        lastEndpoint = endpoint.host() + ":" + endpoint.port();
        LOGGER.info("Hassium: NetworkCore migrating immediately to {} (fault/direct)", endpoint);
        closeOldOutbound();
        connectWithResume(endpoint);
    }

    /** 续流连接：构造票据 + 玩家状态尾（epoch 递增 + 共享密钥签名，T7 验签）后 connect。 */
    private void connectWithResume(MigrationEndpoint endpoint) {
        HandshakeStateTail.C2S tail = migration.buildHandshakeTail(true);
        connect(endpoint.host(), endpoint.port(), tail);
    }

    /** 关闭旧 outbound（写权移交；断连周期语义对称）。 */
    private void closeOldOutbound() {
        OutboundConnection prev = outbound;
        outbound = null;
        if (prev != null) {
            prev.close();
        }
    }

    /** 预热会话接管：旧 outbound 关闭 → 预热连接入站缝接线 → 应用握手 → ACTIVE。 */
    private void promotePrewarm(PrewarmSession warm) {
        migrationResolved = true;
        OutboundConnection conn = warm.connection();
        HandshakeCodec.ServerResponse response = warm.handshakeResponse();
        lastResumeAccepted = warm.resumeAccepted();
        OutboundConnection prev = outbound;
        outbound = conn;
        if (prev != null && prev != conn) {
            prev.close();
        }
        // 入站缝接线（与 attach 相同）：PLAY S2C / 登录 S2C / 配置 S2C / 心跳活动
        conn.setS2CPayloadConsumer(this::onS2CPayload);
        conn.setLoginS2CPayloadConsumer(this::onLoginS2CPayload);
        conn.setConfigS2CPayloadConsumer(this::onConfigS2CPayload);
        conn.setInboundActivityListener(() -> migration.noteInboundActivity());
        migration.bindHeartbeatTarget(conn);
        lastHandshake = response;
        applyHandshake(response);
        migrationAttempts.set(0);
        transition(NetworkCoreState.MIGRATING, NetworkCoreState.ACTIVE);
        LOGGER.info("Hassium: NetworkCore -> ACTIVE (prewarm promoted from {}, resumeAccepted={})",
                warm.endpoint(), warm.resumeAccepted());
    }

    /** 预热回调（迁移引擎驱动；与 migrationResolved 互斥防重入）。 */
    private final PrewarmSession.Callback prewarmCallback = new PrewarmSession.Callback() {
        @Override
        public void onReady(PrewarmSession session) {
            migration.clearPrewarm(session);
            if (!migrationResolved && state.get() == NetworkCoreState.MIGRATING) {
                promotePrewarm(session);
            } else {
                LOGGER.info("Hassium: prewarm ready for {} but switch already resolved — closing",
                        session.endpoint());
                session.close();
            }
        }

        @Override
        public void onFailed(MigrationEndpoint endpoint, Throwable cause) {
            if (!migrationResolved && state.get() == NetworkCoreState.MIGRATING) {
                migrationResolved = true;
                LOGGER.warn("[PREWARM] failed for {} — direct migrate with resume: {}", endpoint,
                        cause.toString());
                closeOldOutbound();
                connectWithResume(endpoint);
            } else {
                LOGGER.info("Hassium: prewarm failed for {} (switch already resolved) — ignore", endpoint);
            }
        }
    };

    /** 持有 outbound（connect 内部调用；同包测试用）。关闭旧连接。 */
    void attach(OutboundConnection connection) {
        OutboundConnection prev = outbound;
        outbound = connection;
        if (prev != null && prev != connection) {
            prev.close();
        }
        // T5：接线入站缝——PLAY S2C（原版包/业务包）与登录 S2C（登录桥接）独立解码
        connection.setS2CPayloadConsumer(this::onS2CPayload);
        connection.setLoginS2CPayloadConsumer(this::onLoginS2CPayload);
        // T10：配置阶段 S2C（CONFIG 帧 → 配置监听器分发）
        connection.setConfigS2CPayloadConsumer(this::onConfigS2CPayload);
        // T8：入站活动 → 迁移引擎 liveness（心跳超时监测）
        connection.setInboundActivityListener(() -> migration.noteInboundActivity());
        migration.bindHeartbeatTarget(connection);
    }

    public OutboundConnection outbound() {
        return outbound;
    }

    // ==================== OutboundConnection.Listener（event loop 线程） ====================

    @Override
    public void onOpen(OutboundConnection connection) {
        if (!transition(NetworkCoreState.CONNECTING, NetworkCoreState.HANDSHAKING)) {
            LOGGER.debug("Hassium: onOpen while state={} (reconnect/migrate: handshake in flight)", state.get());
        }
    }

    @Override
    public void onHandshakeAccepted(HandshakeCodec.ServerResponse response) {
        onHandshakeAccepted(response, false);
    }

    /**
     * 握手接受 + T7 S2C 尾续流结果（T8：迁移续流发起确认）。event loop 线程。
     * MIGRATING → ACTIVE（resumeAccepted 语义）；HANDSHAKING → ACTIVE（初始连接）。
     */
    @Override
    public void onHandshakeAccepted(HandshakeCodec.ServerResponse response, boolean resumeAccepted) {
        lastHandshake = response;
        lastResumeAccepted = resumeAccepted;
        applyHandshake(response);
        boolean wasMigrating = state.get() == NetworkCoreState.MIGRATING;
        if (!transition(NetworkCoreState.HANDSHAKING, NetworkCoreState.ACTIVE)
                && !transition(NetworkCoreState.MIGRATING, NetworkCoreState.ACTIVE)) {
            LOGGER.warn("Hassium: handshake accepted but state={} (unexpected)", state.get());
        }
        if (wasMigrating) {
            migrationAttempts.set(0);
            if (resumeAccepted) {
                LOGGER.info("Hassium: NetworkCore -> ACTIVE (migrated to {}, resumeAccepted=true — 续流就绪)",
                        lastEndpoint);
            } else {
                LOGGER.warn("Hassium: NetworkCore -> ACTIVE (migrated to {}, resumeAccepted=false — "
                        + "会话未附着，数据推送不会流入；登录桥/重连兜底)", lastEndpoint);
            }
        } else {
            LOGGER.info("Hassium: NetworkCore -> ACTIVE (epoch={})",
                    response.udpTail() != null ? response.udpTail().connectionEpoch() : -1L);
        }
    }

    /** 握手响应应用：SeedGen 透传 + UDP 数据面 + ZSTD（幂等；初始连接/迁移/预热接管复用）。 */
    private void applyHandshake(HandshakeCodec.ServerResponse response) {
        try {
            // SeedGen 尾部透传 ClientChunkPipeline 现有状态（与三端内联解码同语义）
            ClientChunkPipeline.getInstance().setServerSeedInfo(
                    response.worldSeed(), response.levelStemNbt(), response.seedGenEnabled());
        } catch (Throwable t) {
            LOGGER.warn("Hassium: setServerSeedInfo failed", t);
        }
        if (response.udpTail() != null && response.udpTail().hasUdpDataplane()) {
            UdpDataPlane.getInstance().start(response.udpTail());
        }
        OutboundConnection oc = outbound;
        if (oc != null) {
            try {
                HassiumConfigService config = HassiumConfigService.getInstance();
                if (response.globalCompressionAccepted() && config.isNetworkCompressionEnabled()) {
                    oc.installZstd(config.getGlobalCompressionThreshold(), config.getGlobalCompressionLevel());
                }
            } catch (Throwable t) {
                LOGGER.warn("Hassium: ZSTD install skipped", t);
            }
        }
    }

    @Override
    public void onHandshakeRejected(String reason) {
        LOGGER.warn("Hassium: NetworkCore handshake rejected: {}", reason);
        OutboundConnection oc = outbound;
        if (oc != null) {
            oc.close();
            outbound = null;
        }
        transitionTo(NetworkCoreState.IDLE);
    }

    @Override
    public void onError(Throwable cause) {
        if (state.get() == NetworkCoreState.MIGRATING) {
            OutboundConnection oc = outbound;
            outbound = null;
            if (oc != null) {
                oc.close();
            }
            MigrationEndpoint target = pendingMigrationTarget;
            if (target != null && migration.prewarmInFlight(target)) {
                // 预热窗口内主连接（旧主控）故障：B 不受 A 故障影响，等预热结果
                // （预热回调驱动切换/直连；迁移不中断）
                LOGGER.warn("Hassium: outbound failed during prewarm to {} — awaiting prewarm result",
                        target);
                return;
            }
            if (!migrationResolved) {
                migrationResolved = true;
                MigrationEndpoint next = migration.nextEndpoint();
                if (next != null && migrationAttempts.incrementAndGet() <= MAX_MIGRATION_ATTEMPTS) {
                    LOGGER.warn("Hassium: migration connection failed ({}), retrying {}", cause.toString(), next);
                    connectWithResume(next);
                    return;
                }
            }
            LOGGER.error("Hassium: migration failed — back to IDLE", cause);
            transitionTo(NetworkCoreState.IDLE);
            return;
        }
        LOGGER.error("Hassium: NetworkCore outbound error", cause);
        OutboundConnection oc = outbound;
        if (oc != null) {
            oc.close();
            outbound = null;
        }
        transitionTo(NetworkCoreState.IDLE);
    }

    // ==================== 契约：入站 S2C / 出站 C2S ====================

    /**
     * 注册入站注入器（T5 实现体：把网关注入为原版 Packet 后 handler 层直调）。
     * 幂等（重复注册忽略）。
     */
    public void registerS2CInjector(Consumer<Packet<?>> injector) {
        if (injector != null) {
            s2cInjectors.addIfAbsent(injector);
        }
    }

    public void unregisterS2CInjector(Consumer<Packet<?>> injector) {
        s2cInjectors.remove(injector);
    }

    /**
     * 注册入站 S2C 注入前的包翻译器（T9 ViaFabric 协议转换缝；{@link ViaFabricCompat#onLogin()} 接线）。
     * 返回 null 表示跳过翻译（原包直进注入器）；翻译器自身抛异常由 dispatchS2C 兜底。
     */
    public void setS2CTranslator(Function<Packet<?>, Packet<?>> translator) {
        s2cTranslator.set(translator);
    }

    public Function<Packet<?>, Packet<?>> s2cTranslator() {
        return s2cTranslator.get();
    }

    /**
     * 入站回调：网关 outbound 解码出的原版 Packet 经此分发（计数可验证；无注入器时仅日志）。
     * T9：若注册了 S2C 翻译器（ViaFabric 协议转换），先转换再进注入器；转换异常自动退回原包。
     */
    public void dispatchS2C(Packet<?> packet) {
        s2cDispatched.incrementAndGet();
        Function<Packet<?>, Packet<?>> translator = s2cTranslator.get();
        if (translator != null) {
            try {
                Packet<?> translated = translator.apply(packet);
                if (translated != null) {
                    packet = translated;
                }
            } catch (Throwable t) {
                LOGGER.error("Hassium: S2C translator failed for {}, direct injection fallback",
                        packet.getClass().getSimpleName(), t);
            }
        }
        if (s2cInjectors.isEmpty()) {
            LOGGER.debug("Hassium: dispatchS2C {} (no injectors yet, count={})",
                    packet.getClass().getSimpleName(), s2cDispatched.get());
            return;
        }
        for (Consumer<Packet<?>> injector : s2cInjectors) {
            try {
                injector.accept(packet);
            } catch (Throwable t) {
                LOGGER.error("Hassium: S2C injector failed", t);
            }
        }
    }

    public long s2cDispatchedCount() {
        return s2cDispatched.get();
    }

    /**
     * outbound PLAY S2C payload 入站（Netty event loop）：解码 → 原版包走 {@link #dispatchS2C}
     * 注入器（handler 层直调），Hassium 业务包走 {@link #dispatchS2CBusiness} 现有收口。
     */
    private void onS2CPayload(ByteBuf payload) {
        try {
            int kind = GatewayPacketCodec.peekKind(payload);
            if (kind == GatewayPacketCodec.KIND_HASSIUM) {
                dispatchS2CBusiness(GatewayPacketCodec.decodeHassium(payload));
                return;
            }
            Packet<?> packet = GatewayPacketCodec.decodeVanilla(
                    payload, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.PLAY,
                    resolveClientRegistryAccess());
            dispatchS2C(packet);
        } catch (Throwable t) {
            LOGGER.error("Hassium: S2C payload decode failed", t);
        } finally {
            payload.release();
        }
    }

    /**
     * outbound 登录 S2C payload 入站（登录桥接，T11 主控配对）：解码为原版登录 S2C 包后
     * 经 {@link #dispatchS2C} 注入器进原版登录监听器（{@code ClientHandshakePacketListenerImpl}
     * 的官方 handler，路由由 {@link GatewayS2CRouter} 的通用分发完成）。
     */
    private void onLoginS2CPayload(ByteBuf payload) {
        try {
            Packet<?> packet = GatewayPacketCodec.decodeVanilla(
                    payload, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.LOGIN,
                    RegistryAccess.EMPTY);
            dispatchS2C(packet);
        } catch (Throwable t) {
            LOGGER.error("Hassium: LOGIN_S2C payload decode failed", t);
        } finally {
            payload.release();
        }
    }

    /**
     * Hassium 业务 S2C 收口（改收口为网关注入）：接现有逻辑
     * （{@link ClientMetadataHandler} / {@link ShadowLightCompute}），计数并入
     * {@link #s2cDispatchedCount()} 可验证。Netty 线程调用；各收口线程安全
     * （hash 后台池、delta 任意线程、BE 主线程队列）。
     */
    private void dispatchS2CBusiness(GatewayPacketCodec.HassiumPacket hp) {
        s2cDispatched.incrementAndGet();
        switch (hp.sub()) {
            case CHUNK_HASH -> {
                // T8：区块 hash 活动 = 增量未收敛信号（空闲窗口判定输入）
                migration.noteChunkHashActivity();
                ClientMetadataHandler.handleChunkHashPacket(
                        (io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket) hp.packet());
            }
            case SECTION_DELTA -> ShadowLightCompute.submitDelta(
                    (io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket) hp.packet());
            case LIGHT_DELTA -> {
                io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket light =
                        (io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket) hp.packet();
                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightDeltaReceived(light.entries().size());
                LOGGER.debug("Hassium: LightDelta {} entries (consumer T6: shadow light invalidation)",
                        light.entries().size());
            }
            case SEED_REF -> ClientMetadataHandler.handleSeedRefPacket(
                    (io.github.limuqy.mc.hassium.network.SeedRefS2CPacket) hp.packet());
            case BLOCK_ENTITY_DATA -> ClientMetadataHandler.handleBlockEntityDataPacket(
                    (io.github.limuqy.mc.hassium.network.BlockEntityDataS2CPacket) hp.packet());
        }
    }

    /**
     * 客户端侧注册表解析（1.20.5+ PLAY 包编解码需要真实 registry 才能还原区块/注册表内容；
     * 登录包用 EMPTY 即可）。1.20.1 段无 RegistryFriendlyByteBuf，恒 null/EMPTY。
     */
    private static RegistryAccess resolveClientRegistryAccess() {
#if MC_VER >= MC_1_20_5
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return RegistryAccess.EMPTY;
        }
        if (mc.level != null) {
            return mc.level.registryAccess();
        }
        // Minecraft.getConnection() = ClientPacketListener（配置/play 期非 null）
        if (mc.getConnection() != null) {
            return mc.getConnection().registryAccess();
        }
        return RegistryAccess.EMPTY;
#else
        return RegistryAccess.EMPTY;
#endif
    }

    /** C2S 编码器：原版 C2S 包 → outbound 控制帧 payload；返回 null 表示跳过。T5 注册真实编码器。 */
    @FunctionalInterface
    public interface C2SEncoder {
        ByteBuf encode(Packet<?> packet);
    }

    public void setC2SEncoder(C2SEncoder encoder) {
        c2sEncoder.set(encoder);
    }

    /** 当前 C2S 编码器（诊断/测试保存恢复用）。 */
    public C2SEncoder c2sEncoder() {
        return c2sEncoder.get();
    }

    /**
     * world 侧出站收口（T5 接入 MixinConnection 截获，T10 完整接线）：原版 C2S 包编码进
     * outbound PACKET_C2S 帧。返回 {@code true} = 已完整编码并交给 outbound（调用方应取消
     * 原版发送——原版连接为壳，C2S 全走网关）；{@code false} = 未路由（原版放行，降级）。
     * <p>
     * 未路由情形：状态非 ACTIVE（CONNECTING/HANDSHAKING 窗口原版直连兜底，避免丢包）、
     * outbound 未开、无编码器、编码失败（未知/自定义包）。
     * 计数 {@link #c2sRoutedCount()} 每次调用 +1（可验证）。
     */
    public boolean routeC2S(Packet<?> packet) {
        c2sRouted.incrementAndGet();
        if (state.get() != NetworkCoreState.ACTIVE) {
            LOGGER.debug("Hassium: routeC2S {} passthrough (state={}, count={})",
                    packet.getClass().getSimpleName(), state.get(), c2sRouted.get());
            return false;
        }
        OutboundConnection oc = outbound;
        C2SEncoder encoder = c2sEncoder.get();
        if (oc == null || !oc.isOpen()) {
            LOGGER.debug("Hassium: routeC2S {} dropped (outbound not open, count={})",
                    packet.getClass().getSimpleName(), c2sRouted.get());
            return false;
        }
        if (encoder == null) {
            LOGGER.debug("Hassium: routeC2S {} (no C2S encoder yet, count={})",
                    packet.getClass().getSimpleName(), c2sRouted.get());
            return false;
        }
        ByteBuf payload = null;
        try {
            payload = encoder.encode(packet);
        } catch (Throwable t) {
            LOGGER.error("Hassium: C2S encode failed for {}", packet.getClass().getSimpleName(), t);
        }
        if (payload != null) {
            oc.sendC2S(payload);
            return true;
        }
        return false;
    }

    public long c2sRoutedCount() {
        return c2sRouted.get();
    }

    // ==================== 登录桥接（中继，T5） ====================

    /**
     * 原版登录 C2S 包中继入口（MixinConnection send HEAD 调用）：登录包 → outbound
     * LOGIN_C2S 帧（与 T11 主控侧配对）。outbound 未开放行原版（不吞包，正确性优先）；
     * 计数 {@link #loginRelayedCount()} 可验证。
     */
    public void relayLoginPacket(Packet<?> packet) {
        if (!isLoginPacket(packet)) {
            return;
        }
        routeLoginC2S(packet);
    }

    /**
     * 登录阶段 C2S 包编码进 outbound（LOGIN 协议帧）。outbound 未开时仅计数 + 日志，
     * vanilla 登录流程照常（网关复刻登录链在 outbound 就绪后接管，REQ §A5）。
     */
    public void routeLoginC2S(Packet<?> packet) {
        loginRelayed.incrementAndGet();
        OutboundConnection oc = outbound;
        if (oc == null || !oc.isOpen()) {
            LOGGER.debug("Hassium: routeLoginC2S {} dropped (outbound not open, count={})",
                    packet.getClass().getSimpleName(), loginRelayed.get());
            return;
        }
        ByteBuf payload = null;
        try {
            payload = GatewayPacketCodec.encodeVanilla(
                    packet, PacketFlow.SERVERBOUND, GatewayPacketCodec.GatewayProtocol.LOGIN,
                    resolveClientRegistryAccess());
        } catch (Throwable t) {
            LOGGER.error("Hassium: LOGIN_C2S encode failed for {}", packet.getClass().getSimpleName(), t);
        }
        if (payload != null) {
            oc.sendLoginC2S(payload);
        }
    }

    /** 已中继的登录 C2S 包计数（可验证）。 */
    public long loginRelayedCount() {
        return loginRelayed.get();
    }

    /** 是否为原版登录阶段 C2S 包（MixinConnection 中继判定；服务端方向恒 false）。 */
    public static boolean isLoginPacket(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.login.ServerboundHelloPacket) {
            return true;
        }
        if (packet instanceof net.minecraft.network.protocol.login.ServerboundKeyPacket) {
            return true;
        }
#if MC_VER < MC_1_20_2
        return packet instanceof net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
#else
        if (packet instanceof net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket) {
            return true;
        }
        return packet instanceof net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
#endif
    }

    // ==================== 配置阶段中继（T10） ====================

    /**
     * 原版配置阶段 C2S 包中继入口（MixinConnection send HEAD 在配置监听器期调用）：
     * 配置包 → outbound CONFIG_C2S 帧（1.20.2+；主控侧按桥监听器阶段解码，推进
     * ServerConfigurationPacketListenerImpl）。纯旁路不 cancel：vanilla 配置流程照常
     * （客户端配置由原版 TCP 完成，镜像仅供主控配置阶段推进/帧链路复刻）。
     * 1.20.1 无配置阶段，恒 false 零开销。
     */
    public void relayConfigPacket(Packet<?> packet) {
        if (!isConfigPacket(packet)) {
            return;
        }
        routeConfigC2S(packet);
    }

    /**
     * 配置阶段 C2S 包编码进 outbound（CONFIG 协议帧）。outbound 未开时仅计数 + 日志，
     * vanilla 配置流程照常（不吞包）。
     */
    public void routeConfigC2S(Packet<?> packet) {
        configRelayed.incrementAndGet();
        OutboundConnection oc = outbound;
        if (oc == null || !oc.isOpen()) {
            LOGGER.debug("Hassium: routeConfigC2S {} dropped (outbound not open, count={})",
                    packet.getClass().getSimpleName(), configRelayed.get());
            return;
        }
        ByteBuf payload = null;
        try {
            payload = GatewayPacketCodec.encodeVanilla(
                    packet, PacketFlow.SERVERBOUND, GatewayPacketCodec.GatewayProtocol.CONFIG,
                    resolveClientRegistryAccess());
        } catch (Throwable t) {
            LOGGER.error("Hassium: CONFIG_C2S encode failed for {}", packet.getClass().getSimpleName(), t);
        }
        if (payload != null) {
            oc.sendConfigC2S(payload);
        }
    }

    /** 已中继的配置阶段 C2S 包计数（可验证）。 */
    public long configRelayedCount() {
        return configRelayed.get();
    }

    /**
     * 是否为原版配置阶段 C2S 包（1.20.2+；MixinConnection 中继判定）。
     * 1.20.1 无配置协议，恒 false。
     */
    public static boolean isConfigPacket(Packet<?> packet) {
#if MC_VER < MC_1_20_2
        return false;
#else
        if (packet instanceof net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket) {
            return true;
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundKeepAlivePacket) {
            return true;
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundClientInformationPacket) {
            return true;
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket) {
            return true;
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundPongPacket) {
            return true;
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundResourcePackPacket) {
            return true;
        }
#if MC_VER >= MC_1_20_5
        if (packet instanceof net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks) {
            return true;
        }
        if (packet instanceof net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket) {
            return true;
        }
#endif
        return false;
#endif
    }

    /**
     * outbound 配置阶段 S2C payload 入站（T10）：解码为原版 configuration S2C 包后经
     * {@link #dispatchS2C} 分发（配置监听器期由 {@link GatewayS2CRouter} 回退到 vanilla
     * Connection 的配置监听器，见 dispatchToListener）。
     */
    private void onConfigS2CPayload(ByteBuf payload) {
        try {
            Packet<?> packet = GatewayPacketCodec.decodeVanilla(
                    payload, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.CONFIG,
                    RegistryAccess.EMPTY);
            dispatchS2C(packet);
        } catch (Throwable t) {
            LOGGER.error("Hassium: CONFIG_S2C payload decode failed", t);
        } finally {
            payload.release();
        }
    }

    /**
     * 客户端 vanilla Connection 暂存（T10 MixinConnection 截获期登记）：配置阶段
     * {@code Minecraft.connection} 为 null，CONFIG_S2C 分发需要回退到 vanilla 连接的
     * 配置监听器（{@link GatewayS2CRouter#dispatchToListener}）。登录期同时被登录中继
     * 使用。断连（onDisconnect）时清空。
     */
    public void setVanillaConnection(net.minecraft.network.Connection connection) {
        this.vanillaConnection = connection;
    }

    /** 最近暂存的 vanilla Connection（未暂存/已断连 → null）。 */
    public net.minecraft.network.Connection vanillaConnection() {
        return vanillaConnection;
    }

    // ==================== 迁移引擎（T8：Sink + 配置透传） ====================

    /** 故障触发（引擎心跳超时）：立即切换；无目标端点 → 降级 IDLE。 */
    @Override
    public void onFault() {
        if (state.get() != NetworkCoreState.ACTIVE) {
            LOGGER.debug("Hassium: fault trigger ignored (state={})", state.get());
            return;
        }
        MigrationEndpoint target = migration.nextEndpoint();
        if (target != null) {
            LOGGER.warn("[FAILOVER] fault trigger — migrating to {}", target);
            migrateToImmediate(target);
        } else {
            LOGGER.error("[FAILOVER] fault trigger but no target endpoint configured — dropping to IDLE");
            closeOldOutbound();
            transitionTo(NetworkCoreState.IDLE);
        }
    }

    /** 策略触发（负载阈值/维护窗口，引擎 onLoadReport）：预热感知迁移；无目标 → 跳过。 */
    @Override
    public void onPolicyTrigger(String reason) {
        if (state.get() != NetworkCoreState.ACTIVE) {
            LOGGER.debug("Hassium: policy trigger ignored (state={})", state.get());
            return;
        }
        MigrationEndpoint target = migration.nextEndpoint();
        if (target != null) {
            LOGGER.info("[MIGRATE] policy trigger ({}) — migrating to {}", reason, target);
            migrateTo(target);
        } else {
            LOGGER.info("[MIGRATE] policy trigger ({}) but no target endpoint configured — skip", reason);
        }
    }

    /** 迁移引擎实例（策略/端点/源注入与观测；T10/命令接线入口）。 */
    public MigrationEngine migration() {
        return migration;
    }

    /** 最近一次握手续流结果（迁移/预热接管后为 true）。 */
    public boolean lastResumeAccepted() {
        return lastResumeAccepted;
    }

    /** 客户端当前玩家状态（迁移票据位置上报源；无玩家 → absent）。 */
    private static PlayerStateReport clientPlayerState() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return PlayerStateReport.absent();
        }
        net.minecraft.world.entity.player.Player player = mc.player;
        String dimension = player.level().dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
        return new PlayerStateReport(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), dimension);
    }

    /** 客户端玩家 UUID（续流票据身份；1.20.1 起 User.getProfileId 全锚点可用）。 */
    private static UUID clientPlayerId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getUser() == null) {
            return null;
        }
        return mc.getUser().getProfileId();
    }

    /**
     * T10 标准流程握手尾：玩家状态 + UUID（无续流票据）。主控据此在非续流路径把网关
     * 会话附着到 vanilla 物化玩家（握手晚于登录完成时直接挂 C2S sink，无需登录桥）。
     * 环境不可用（无玩家/无 profile）时返回可安全发送的最小尾（playerId=null，主控
     * 回退登录桥路径）。
     */
    private static HandshakeStateTail.C2S buildAutoTail() {
        return new HandshakeStateTail.C2S(clientPlayerState(), false, null, clientPlayerId());
    }

    // ==================== 状态机 ====================

    public NetworkCoreState state() {
        return state.get();
    }

    /** CAS 转移；成功返回 true 并日志。 */
    public boolean transition(NetworkCoreState from, NetworkCoreState to) {
        if (state.compareAndSet(from, to)) {
            LOGGER.info("Hassium: NetworkCore state {} -> {}", from, to);
            return true;
        }
        return false;
    }

    private void transitionTo(NetworkCoreState to) {
        NetworkCoreState prev = state.getAndSet(to);
        if (prev != to) {
            LOGGER.info("Hassium: NetworkCore state {} -> {}", prev, to);
        }
    }

    /** 最近一次握手响应（主控会话身份；未握手为 null）。 */
    public HandshakeCodec.ServerResponse lastHandshake() {
        return lastHandshake;
    }

    /** 最近一次 outbound 目标端点（"host:port"）。 */
    public String lastEndpoint() {
        return lastEndpoint;
    }

    // ==================== 内部 ====================

    /**
     * 尽力自动连接当前登录服务器（vanilla Connection 的 ServerData.ip，"host[:port]"）。
     * 网络压缩关闭时跳过；失败仅告警（正式地址源 = T7 迁移引擎）。
     */
    private void autoConnect() {
        try {
            HassiumConfigService config = HassiumConfigService.getInstance();
            if (!config.isNetworkCompressionEnabled()) {
                LOGGER.debug("Hassium: NetworkCore auto-connect skipped (net.enabled=false)");
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null || mc.getConnection().getServerData() == null) {
                return;
            }
            String ip = mc.getConnection().getServerData().ip;
            if (ip == null || ip.isEmpty()) {
                return;
            }
            String host = ip;
            int port = 25565;
            int colon = ip.lastIndexOf(':');
            if (colon > 0) {
                try {
                    port = Integer.parseInt(ip.substring(colon + 1));
                    host = ip.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // 无端口后缀（或 IPv6 字面量），整体按 host 处理
                }
            }
            connect(host, port);
        } catch (Throwable t) {
            LOGGER.debug("Hassium: NetworkCore auto-connect skipped", t);
        }
    }
}
