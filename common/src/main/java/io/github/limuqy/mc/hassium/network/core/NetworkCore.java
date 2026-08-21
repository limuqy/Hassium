package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.network.ClientEndpointStore;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import io.github.limuqy.mc.hassium.network.GatewayInfoCodec;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationEndpoint;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationEngine;
import io.github.limuqy.mc.hassium.network.core.migration.PrewarmSession;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ChunkApplyAck;
import io.github.limuqy.mc.hassium.network.core.outbound.OutboundConnection;
import io.github.limuqy.mc.hassium.network.core.outbound.UdpDataPlane;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.github.limuqy.mc.hassium.network.core.viafabric.ViaFabricCompat;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import io.github.limuqy.mc.hassium.server.GatewayPlayerBridge;
import io.github.limuqy.mc.hassium.platform.Services;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Optional;

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
    /** M1 bootstrap：最近一次收到的 gateway_info（onDisconnect 清空；onLogin 不清——1.20.1
     *  上 custom payload 在 Netty 线程先于主线程 handleLogin 到达，onLogin 清空会让
     *  autoConnect 走探测而丢弃已到的下发端点；autoConnect ② 与 onGatewayInfo 共用）。 */
    private volatile GatewayInfoCodec.GatewayInfo lastGatewayInfo;
    /** M2 端点存储（懒建；config/hassium/failover-endpoints.properties，CONTRACTS §4 format=2）。 */
    private volatile ClientEndpointStore endpointStore;
    /** M3 仅网关登录会话（主连接失效恢复，仅网关登录）；非 null = 仅网关模式激活
     *  （登录期 + PLAY 期——PLAY keep-alive 无壳路径依赖；onDisconnect 清）。 */
    private volatile GatewayOnlyLogin gatewayOnlyLogin;
    /** M3 最近一次原版连接意图（MixinConnectScreen startConnecting HEAD 捕获；失败决策用）。 */
    private volatile net.minecraft.client.multiplayer.ServerData gatewayOnlyServerData;
    private volatile net.minecraft.client.gui.screens.Screen gatewayOnlyParentScreen;
    /** M3 当前连接意图已尝试过仅网关登录（防 setScreen 拦截重入循环；新意图复位）。 */
    private volatile boolean gatewayOnlyAttempted;

    /** L1 迁移引擎（REQ C 节）：触发判定 + 迁移编排 + 预热。 */
    private final MigrationEngine migration = new MigrationEngine();

    /** 迁移中目标端点（故障/回调决策用）。 */
    private volatile MigrationEndpoint pendingMigrationTarget;
    /** 切换动作已落地（预热回调防重入；迁移成功/回退时复位）。
     *  review-fix: T1-68 promotePrewarm 并发重入门闩——AtomicBoolean.compareAndSet
     *  保证 migrateTo 主线程与 event loop onReady 双路径仅一个 promote 落地。 */
    private final AtomicBoolean migrationResolved = new AtomicBoolean(false);
    /** 迁移连接尝试计数（成功/新会话复位）。 */
    private final AtomicLong migrationAttempts = new AtomicLong();

    private static final long MAX_MIGRATION_ATTEMPTS = 3;

    /** A-M2: 握手总超时（从 CONNECTING 起算；到期同握手期故障兜底：断 outbound → IDLE 原版直连）。 */
    private static final long HANDSHAKE_TIMEOUT_MS = 15_000L;
    /** M2 端点存储文件（相对 config/，与 CONFIG_CLIENT_FILE 同目录族 config/hassium/）。 */
    private static final String FAILOVER_ENDPOINTS_FILE = "hassium/failover-endpoints.properties";
    /** A-M2: 最近一次 connect 的握手 deadline（event loop 定时任务到期自检；0=未握手期）。 */
    private volatile long handshakeDeadlineMs;

    /** T0b 诊断：握手各阶段耗时——各状态进入时刻（wall ms；0=本会话未进入）。 */
    private volatile long connectingAtMs;
    private volatile long handshakingAtMs;
    private volatile long stateEnteredAtMs;

    private NetworkCore() {
        // T5：S2C 注入器（handler 层直调路由）随单例就位；outbound payload 缝在 attach 时接线
        registerS2CInjector(GatewayS2CRouter.INSTANCE);
        // T10：C2S 编码器真实接线——PLAY SERVERBOUND 原版包 → 控制帧 payload（routeC2S 收口）。
        // 未知/自定义包 encode 抛异常由 routeC2S 兜底（返回 false → 原版放行），不吞包。
        c2sEncoder.set(packet -> GatewayPacketCodec.encodeVanilla(
                packet, PacketFlow.SERVERBOUND, GatewayPacketCodec.GatewayProtocol.PLAY,
                resolveClientRegistryAccess()));
        // T8：迁移引擎接线——故障/策略 sink = 本核心；玩家身份/位置源 = 客户端会话；
        // B2 全链接线：master.migration* 键族（minTps/负载/维护窗口/心跳/静默超时/空闲窗口）
        // 经 getMigrationPolicyConfig 应用；silentTimeout 未配置时回退 migrationFaultTimeoutMs 语义
        migration.setSink(this);
        migration.setPlayerStateSource(NetworkCore::clientPlayerState);
        migration.setPlayerIdSource(NetworkCore::clientPlayerId);
        try {
            migration.applyMigrationPolicyFromConfig(
                    HassiumConfigService.getInstance().getMigrationPolicyConfig());
        } catch (Throwable t) {
            LOGGER.debug("Hassium: migration policy config read skipped", t);
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 玩家登录（vanilla login 完成后，主线程）：按 outbound 现状分三支——
     * <ul>
     *   <li>仅网关登录（{@link #gatewayOnlyLogin} 非 null）：保留 ACTIVE outbound
     *       （主控会话 = 唯一会话；close/autoConnect 会杀服务端会话并重连冲突），仅复位。</li>
     *   <li>gateway-bootstrap 活连接（outbound 非 null 且非 IDLE）：bootstrap 握手已完成
     *       （ACTIVE）或在途（CONNECTING/HANDSHAKING）——1.20.1 custom payload 先于主线程
     *       handleLogin 到达，onLogin 时握手已建立 → 保留现有 outbound，不 close、不重连
     *       （close 会让服务端 peer closed 移除玩家会话 → 数据面断，T5 冒烟 R1 根因）。</li>
     *   <li>无活连接：关闭陈旧 outbound（正常为 null）、清零计数、进入 CONNECTING，并按当前
     *       连接地址尝试自动建立 outbound（失败仅告警，正式地址源为 T7 迁移引擎）。</li>
     * </ul>
     * 三分支均复位连接周期计数/握手标记/迁移尝试（{@link #resetSessionCounters}）；唯一例外：
     * outbound 跨 onLogin 保留的分支（仅网关登录 / gateway-bootstrap）不复位 s2cDispatched——
     * 同一 outbound 连接周期内 pre-login 已到达并 dispatch 的 S2C（1.21.1 R1 冒烟：ACTIVE 与
     * onLogin 间服务端已推 114 条 chunk hash）应保留计数，否则 world ready 后 dump 恒为 0。
     */
    public void onLogin() {
        GatewayOnlyLogin session = gatewayOnlyLogin;
        if (session != null) {
            session.onLoginCompleted();
            // L2 修复：仅网关登录 = 同一 outbound 连接周期，pre-login 登录桥 dispatch 计数保留
            resetSessionCounters(false);
            // T9：ViaFabric 兼容——新会话重探测 + 重置转换桥（旧 UserConnection 失效）并接线翻译器
            ViaFabricCompat.INSTANCE.onLogin();
            // T8：迁移引擎心跳监测随会话启动（守护线程；测试不经过此路径）
            migration.start();
            if (state.get() != NetworkCoreState.ACTIVE) {
                LOGGER.warn("Hassium: gateway-only login completed but state={} (expected ACTIVE)", state.get());
            }
            LOGGER.info("Hassium: NetworkCore onLogin (gateway-only) — outbound kept ACTIVE");
            return;
        }
        // M3 gateway-bootstrap：bootstrap 握手已完成（ACTIVE）或在途（CONNECTING/HANDSHAKING）
        // → 保留现有 outbound，不重连。修复（T5 冒烟 R1 根因）：onLogin 无条件 close 会把
        // bootstrap 已 ACTIVE 的握手连接杀掉 → 服务端 peer closed 移除玩家会话 → 数据面断。
        // 1.20.1 上 custom payload 在 Netty 线程先于主线程 handleLogin 到达，onLogin 时握手已完成。
        NetworkCoreState st = state.get();
        if (outbound != null && st != NetworkCoreState.IDLE) {
            // L2 修复（1.21.1 R1 冒烟 gatewayS2c=0 根因）：bootstrap outbound 跨 onLogin 保留，
            // pre-login 已 dispatch 的 S2C（chunk hash）计数不复位
            resetSessionCounters(false);
            ViaFabricCompat.INSTANCE.onLogin();
            migration.start();
            if (st != NetworkCoreState.ACTIVE) {
                LOGGER.warn("Hassium: NetworkCore onLogin with bootstrap outbound in state {} "
                        + "(expected ACTIVE) — keeping, no reconnect", st);
            }
            LOGGER.info("Hassium: NetworkCore onLogin (bootstrap) — outbound kept (state={}), "
                    + "no reconnect", st);
            return;
        }
        OutboundConnection stale = outbound;
        if (stale != null) {
            stale.close();
            outbound = null;
        }
        // 新连接分支：新连接周期从零开始，全量复位（含 s2cDispatched）
        resetSessionCounters(true);
        ViaFabricCompat.INSTANCE.onLogin();
        migration.start();
        transitionTo(NetworkCoreState.CONNECTING);
        LOGGER.info("Hassium: NetworkCore onLogin -> CONNECTING");
        autoConnect();
    }

    /** 会话周期复位（onLogin 三分支共用）：连接周期计数/握手标记/迁移尝试归零。
     *  {@code resetS2cDispatched}=true 时 s2cDispatched 一并清零（新连接分支）；
     *  false 时保留（仅网关登录 / bootstrap 分支——outbound 跨 onLogin 为同一连接周期，
     *  pre-login 已 dispatch 的 S2C 属本周期真实计数，L2 修复）。其余计数器语义三分支不变。 */
    private void resetSessionCounters(boolean resetS2cDispatched) {
        if (resetS2cDispatched) {
            s2cDispatched.set(0);
        }
        c2sRouted.set(0);
        loginRelayed.set(0);
        configRelayed.set(0);
        lastHandshake = null;
        lastResumeAccepted = false;
        migrationAttempts.set(0);
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
        lastGatewayInfo = null;
        // M3：仅网关模式结束（本地壳连接随 disconnect 拆除；下一连接意图重新捕获决策）
        gatewayOnlyLogin = null;
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
     * M1 bootstrap：服务端经 vanilla 通道下发网关信息（channel=hassium:gateway_info，
     * 主线程——客户端 Connection.tick 泵 handleCustomPayload → {@link
     * io.github.limuqy.mc.hassium.network.ClientGatewayBootstrap} → 本方法）。
     * <p>语义（CONTRACTS §3）：
     * <ul>
     *   <li>端点池非空 → 并入迁移候选池（setTargetEndpoints，供迁移环形推进）</li>
     *   <li>outbound 非 ACTIVE（IDLE/CONNECTING/HANDSHAKING）→ close 旧竞态连接 →
     *       connect(端点[0], 下发 authToken)——bootstrap 端点优先于配置/探测路径；
     *       CONNECTING/HANDSHAKING 且 in-flight 目标已 = 端点[0]（gateway_info 迟到/重入）→
     *       保留 in-flight 连接不重连（重复 supersede 会杀自己刚建立的握手，T5 冒烟 R2 根因）</li>
     *   <li>已 ACTIVE → 仅并入候选池，不发起重连（配置/探测已握手成功）</li>
     *   <li>端点池为空（服务端未配置端点、监听默认端口 25566）→ 仅记录，不连接</li>
     * </ul>
     */
    public void onGatewayInfo(GatewayInfoCodec.GatewayInfo info) {
        if (info == null) {
            return;
        }
        lastGatewayInfo = info;
        List<GatewayInfoCodec.Endpoint> eps = info.endpoints();
        if (eps.isEmpty()) {
            LOGGER.info("Hassium: gateway_info received (proto={}, no endpoints — server on default gateway port)",
                    info.protocolVersion());
            return;
        }
        List<MigrationEndpoint> pool = eps.stream()
                .map(e -> new MigrationEndpoint(e.host(), e.port()))
                .toList();
        migration.setTargetEndpoints(pool);
        NetworkCoreState st = state.get();
        if (st == NetworkCoreState.ACTIVE) {
            LOGGER.info("Hassium: gateway_info merged {} endpoint(s) into migration pool (already ACTIVE)",
                    pool.size());
            return;
        }
        GatewayInfoCodec.Endpoint first = eps.get(0);
        if (st == NetworkCoreState.CONNECTING || st == NetworkCoreState.HANDSHAKING) {
            // M3 gateway-bootstrap：in-flight 连接已指向下发端点[0] → 保留（gateway_info 可能
            // 迟到/重入，重复 supersede 会杀掉自己刚建立的握手连接 → 服务端会话被移除；
            // T5 冒烟 R2 根因：in-flight 25567 == 下发 25567 仍被杀一次）。
            String target = first.host() + ":" + first.port();
            if (target.equals(lastEndpoint)) {
                LOGGER.info("Hassium: gateway_info endpoint matches in-flight connection ({}) — "
                        + "keeping, no reconnect", target);
                return;
            }
            LOGGER.warn("Hassium: gateway_info supersedes in-flight connection (state={}) — "
                    + "reconnect to bootstrap endpoint", st);
            closeOldOutbound();
        }
        connect(first.host(), first.port(), buildAutoTail(), info.authToken());
        LOGGER.info("Hassium: gateway_info bootstrap connect -> {}:{} (auth={})",
                first.host(), first.port(), info.authToken().isEmpty() ? "none" : "configured");
    }

    /**
     * 建立到主控的 outbound 连接（异步）。IDLE 时先转 CONNECTING；
     * ACTIVE/MIGRATING 中调用 = 重连/切换（旧 outbound 关闭）。
     * 握手自动携带 T10 标准流程尾（玩家 UUID/位置，主控据此把会话附着到 vanilla 物化玩家）。
     */
    public void connect(String host, int port) {
        connect(host, port, buildAutoTail(), null);
    }

    /**
     * 建立到主控的 outbound 连接（异步）。IDLE 时先转 CONNECTING；
     * ACTIVE/MIGRATING 中调用 = 重连/切换（旧 outbound 关闭）。
     * tail 非 null 时握手携带 T7/T10 状态尾（T8 迁移续流发起 / T10 标准流程身份附着）。
     */
    public void connect(String host, int port, HandshakeStateTail.C2S tail) {
        connect(host, port, tail, null);
    }

    /**
     * 建立到主控的 outbound 连接（异步）+ 连接级鉴权 token（M1 bootstrap 下发）。
     * IDLE 时先转 CONNECTING；ACTIVE/MIGRATING 中调用 = 重连/切换（旧 outbound 关闭）。
     * authToken 为 null 时握手回退 {@link #bootstrapAuthToken()}（gateway_info 下发）；
     * 空串 = 显式不鉴权（线格式不追加 token 字节）。
     * tail 非 null 时握手携带 T7/T10 状态尾（T8 迁移续流发起 / T10 标准流程身份附着）。
     */
    public void connect(String host, int port, HandshakeStateTail.C2S tail, String authToken) {
        if (state.get() == NetworkCoreState.IDLE) {
            transition(NetworkCoreState.IDLE, NetworkCoreState.CONNECTING);
            try {
                io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper.startShadowIfConfigured();
            } catch (Throwable t) {
                LOGGER.debug("Hassium: early shadow start on CONNECTING skipped", t);
            }
        }
        // A-M2: 握手总超时起算（从 CONNECTING 起算 15s；onOpen 时在 event loop 排到期任务）
        handshakeDeadlineMs = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
        lastEndpoint = host + ":" + port;
        migration.noteCurrentEndpoint(new MigrationEndpoint(host, port));
        attach(createOutbound(host, port, tail, authToken));
        LOGGER.info("Hassium: NetworkCore connecting outbound to {}", lastEndpoint);
    }

    /** 经迁移引擎连接工厂创建 outbound（默认真实 NIO；测试注入 EmbeddedChannel 缝）。 */
    private OutboundConnection createOutbound(String host, int port, HandshakeStateTail.C2S tail, String authToken) {
        return migration.createConnection(host, port,
                HandshakeCodec.ClientRequestOptions.defaults(), tail, this, authToken);
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
        migrationResolved.set(false);
        lastEndpoint = endpoint.host() + ":" + endpoint.port();
        // review-fix: T1-66 空闲窗口判定未接线迁移决策（YAGNI 移除），仅保留 prewarm 参数
        LOGGER.info("Hassium: NetworkCore migrating to {} (prewarm={})",
                endpoint, migration.policy().prewarmEnabled());
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
        migrationResolved.set(false);
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
        // review-fix: T1-68 CAS 门闩——双路径（migrateTo 主线程 / event loop onReady）
        // 竞争 promote，仅胜者执行 applyHandshake/UDP start（重复执行靠幂等吸收的问题消除）
        if (!migrationResolved.compareAndSet(false, true)) {
            LOGGER.info("Hassium: promotePrewarm skipped — migration already resolved ({})",
                    warm.endpoint());
            return;
        }
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
            if (!migrationResolved.get() && state.get() == NetworkCoreState.MIGRATING) {
                promotePrewarm(session);
            } else {
                LOGGER.info("Hassium: prewarm ready for {} but switch already resolved — closing",
                        session.endpoint());
                session.close();
            }
        }

        @Override
        public void onFailed(MigrationEndpoint endpoint, Throwable cause) {
            if (!migrationResolved.get() && state.get() == NetworkCoreState.MIGRATING) {
                migrationResolved.set(true);
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
        // M3 仅网关登录：outbound 打开 → 会话发送登录 hello（主线程）
        GatewayOnlyLogin session = gatewayOnlyLogin;
        if (session != null) {
            session.onOutboundOpen();
        }
        // A-M2: 握手总超时（15s 从 connect()/CONNECTING 起算）——event loop 到期自检：
        // 仍处握手期 → onFault 兜底（断 outbound → IDLE 原版直连）；已 ACTIVE/MIGRATING 无操作。
        scheduleHandshakeTimeout(connection);
    }

    /**
     * A-M2: 握手总超时定时任务（event loop；15s 从 CONNECTING 起算）。到期自检状态与
     * deadline——握手完成（ACTIVE）/迁移中（MIGRATING）/已断（IDLE）均无操作；重连会
     * 推进 deadline，旧任务到期因 deadline 已更新而跳过。
     */
    private void scheduleHandshakeTimeout(OutboundConnection connection) {
        long deadline = handshakeDeadlineMs;
        if (deadline <= 0) {
            return; // 未经 connect()（测试直接 attach / 预热接管），无握手超时语义
        }
        long remaining = deadline - System.currentTimeMillis();
        try {
            connection.channel().eventLoop().schedule(() -> {
                if (System.currentTimeMillis() < handshakeDeadlineMs) {
                    return; // 重连已推进 deadline，旧任务作废
                }
                NetworkCoreState st = state.get();
                if (st == NetworkCoreState.CONNECTING || st == NetworkCoreState.HANDSHAKING) {
                    LOGGER.warn("Hassium: handshake timed out after {}ms (state={}) — "
                            + "fall back to vanilla direct connection (N1)", HANDSHAKE_TIMEOUT_MS, st);
                    onFault();
                }
            }, Math.max(0L, remaining), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LOGGER.debug("Hassium: handshake timeout scheduling skipped (event loop unavailable)", t);
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
        // review-fix: T1-M3 协议版本不匹配 → 走拒绝路径（关 outbound → IDLE）
        if (response.protocolVersion() != Constants.CURRENT_PROTOCOL_VERSION) {
            LOGGER.warn("Hassium: NetworkCore handshake protocol mismatch (got={}, expected={})",
                    response.protocolVersion(), Constants.CURRENT_PROTOCOL_VERSION);
            onHandshakeRejected("protocol version mismatch");
            return;
        }
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
            // T0b 诊断：握手各阶段耗时（wall ms；初始连接路径）
            long now = System.currentTimeMillis();
            if (connectingAtMs > 0L && handshakingAtMs > 0L) {
                LOGGER.info("[HANDSHAKE-DIAG] CONNECTING->HANDSHAKING={}ms HANDSHAKING->ACTIVE={}ms total={}ms",
                        handshakingAtMs - connectingAtMs, now - handshakingAtMs, now - connectingAtMs);
            }
        }
        // N1：续流就绪（resumeAccepted）→ 位置回退到断线时上报快照（服务端续流物化权威位置）
        if (resumeAccepted) {
            rollbackPlayerPosition();
        }
    }

    /**
     * N1 位置回退（resumeAccepted 后，event loop 线程）：客户端本地预测在掉线/切换窗口
     * 继续前进，服务端续流物化在「握手尾上报位置」——恢复后把客户端位置回退到该快照，
     * 消除预测漂移。快照源 = 当前 outbound 握手尾 {@link HandshakeStateTail.C2S#state()}
     * （续流握手时经 {@link #connect(String, int, HandshakeStateTail.C2S)} 携带；预热路径
     * promotePrewarm 不走本方法）。经 {@link #dispatchS2C} 注入
     * {@code ClientboundPlayerPositionPacket}（GatewayS2CRouter 通用分发 → 官方
     * handleMovePlayer，绝对坐标；非主线程自动 MainThreadDispatcher 排队）。
     * 维度守卫：客户端玩家存在且当前维度 ≠ 快照维度时跳过（坐标语义不同）。
     */
    private void rollbackPlayerPosition() {
        OutboundConnection oc = outbound;
        HandshakeStateTail.C2S tail = oc != null ? oc.handshakeTail() : null;
        PlayerStateReport snap = tail != null ? tail.state() : null;
        if (snap == null || !snap.present()) {
            LOGGER.debug("Hassium: resume rollback skipped (no snapshot in handshake tail)");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            String currentDim = mc.player.level().dimension()
#if MC_VER < MC_1_21_11
                    .location()
#else
                    .identifier()
#endif
                    .toString();
            if (!currentDim.equals(snap.dimension())) {
                LOGGER.info("Hassium: resume rollback skipped (dimension {} != snapshot {})",
                        currentDim, snap.dimension());
                return;
            }
        }
        LOGGER.info("Hassium: resume rollback -> {}", snap.describe());
        dispatchS2C(buildRollbackPositionPacket(snap));
    }

    /** 快照 PlayerStateReport → ClientboundPlayerPositionPacket（绝对坐标；id=0 无确认语义）。 */
    private static Packet<?> buildRollbackPositionPacket(PlayerStateReport snap) {
#if MC_VER < MC_1_21_2
        // 1.20.1~1.21.1：类形式 (x, y, z, yRot, xRot, relatives, id)
        return new net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket(
                snap.x(), snap.y(), snap.z(), snap.yaw(), snap.pitch(), java.util.Set.of(), 0);
#else
        // 1.21.2+：record (id, PositionMoveRotation, relatives)；deltaMovement=ZERO（回退即停）
        return new net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket(
                0,
                new net.minecraft.world.entity.PositionMoveRotation(
                        new net.minecraft.world.phys.Vec3(snap.x(), snap.y(), snap.z()),
                        net.minecraft.world.phys.Vec3.ZERO,
                        snap.yaw(), snap.pitch()),
                java.util.Set.of());
#endif
    }

    /** 握手响应应用：SeedGen 透传 + UDP 数据面 + ZSTD + 通告端点消费（幂等；初始连接/迁移/预热接管复用）。 */
    private void applyHandshake(HandshakeCodec.ServerResponse response) {
        try {
            // SeedGen 尾部透传 ClientChunkPipeline 现有状态（与三端内联解码同语义）
            ClientChunkPipeline.getInstance().setServerSeedInfo(
                    response.worldSeed(), response.levelStemNbt(), response.seedGenEnabled());
            ShadowLightCompute.flushDeferredRemoteHashes();
        } catch (Throwable t) {
            LOGGER.warn("Hassium: setServerSeedInfo failed", t);
        }
        if (response.udpTail() != null && response.udpTail().hasUdpDataplane()) {
            UdpDataPlane.getInstance().start(response.udpTail());
        }
        // B1：主控握手通告的控制端点池（udp=false 时仍写 controls）→ 迁移候选目标。
        // 空列表不覆盖 = 编程注入兜底（测试/迁移命令先 setTargetEndpoints 后握手）。
        UdpDataPlaneHandshakeTail.S2CTail udpTail = response.udpTail();
        if (udpTail != null && !udpTail.controlEndpoints().isEmpty()) {
            List<MigrationEndpoint> advertised = udpTail.controlEndpoints().stream()
                    .map(ce -> new MigrationEndpoint(ce.host(), ce.port()))
                    .toList();
            migration.setTargetEndpoints(advertised);
            // M2：通告端点落盘（format=2 端点存储；主地址不可得/写入失败仅跳过，不阻断握手）
            persistAdvertisedEndpoints(udpTail.controlEndpoints());
            LOGGER.info("Hassium: NetworkCore handshake advertised {} control endpoint(s): {}",
                    advertised.size(), advertised);
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

    /**
     * M2 端点存储写入：握手成功通告的 controlEndpoints 按逻辑主地址（当前会话 vanilla
     * ServerData.ip，event loop 线程现取）持久化到 failover-endpoints.properties（format=2）。
     * 主地址不可得或写入失败 → 仅告警跳过，不影响握手/迁移。
     */
    private void persistAdvertisedEndpoints(List<UdpDataPlaneHandshakeTail.ControlEndpoint> advertised) {
        try {
            String mainAddress = currentMainAddress();
            if (mainAddress == null) {
                LOGGER.debug("Hassium: endpoint store write skipped (no main address)");
                return;
            }
            List<HassiumConfig.ReachableEndpoint> endpoints = advertised.stream()
                    .map(ce -> new HassiumConfig.ReachableEndpoint(ce.host(), ce.port(), ce.priority()))
                    .toList();
            endpointStore().record(mainAddress, endpoints);
            LOGGER.info("Hassium: endpoint store recorded {} endpoint(s) for {}", endpoints.size(), mainAddress);
        } catch (Throwable t) {
            LOGGER.warn("Hassium: endpoint store write skipped", t);
        }
    }

    /** 当前会话逻辑主地址（ServerData.ip）；不可得 → null（调用方跳过写入）。 */
    private String currentMainAddress() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getConnection() != null && mc.getConnection().getServerData() != null) {
                String ip = mc.getConnection().getServerData().ip;
                if (ip != null && !ip.isEmpty()) {
                    return ip;
                }
            }
        } catch (Throwable ignored) {
            // 不可得 → 跳过写入
        }
        return null;
    }

    /** M2 端点存储（懒建；路径 = config/hassium/failover-endpoints.properties）。 */
    private ClientEndpointStore endpointStore() {
        ClientEndpointStore store = endpointStore;
        if (store == null) {
            synchronized (this) {
                store = endpointStore;
                if (store == null) {
                    store = new ClientEndpointStore(
                            Services.PLATFORM.getConfigDirectory().resolve(FAILOVER_ENDPOINTS_FILE));
                    endpointStore = store;
                }
            }
        }
        return store;
    }


    /**
     * M3 仅网关登录期连接/握手失败（CONNECTING/HANDSHAKING）：关 outbound → 会话
     * 重试下一 store 端点（池耗尽由会话收尾）。非仅网关登录期 / 非握手期 → false
     * （走既有迁移/ACTIVE 语义）。
     */
    private boolean handleGatewayOnlyFailure() {
        GatewayOnlyLogin session = gatewayOnlyLogin;
        if (session == null) {
            return false;
        }
        NetworkCoreState st = state.get();
        if (st != NetworkCoreState.CONNECTING && st != NetworkCoreState.HANDSHAKING) {
            return false;
        }
        OutboundConnection oc = outbound;
        outbound = null;
        if (oc != null) {
            oc.close();
        }
        session.onOutboundFailed();
        return true;
    }

    public void onHandshakeRejected(String reason) {
        LOGGER.warn("Hassium: NetworkCore handshake rejected: {}", reason);
        if (handleGatewayOnlyFailure()) {
            return;
        }
        OutboundConnection oc = outbound;
        if (oc != null) {
            oc.close();
            outbound = null;
        }
        transitionTo(NetworkCoreState.IDLE);
    }

    @Override
    public void onError(Throwable cause) {
        if (handleGatewayOnlyFailure()) {
            LOGGER.warn("Hassium: outbound failed during gateway-only login — retry next endpoint: {}",
                    cause.toString());
            return;
        }
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
            if (!migrationResolved.get()) {
                migrationResolved.set(true);
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
        if (state.get() == NetworkCoreState.ACTIVE) {
            // A-M1: ACTIVE 期被动断链（TCP 硬断 channelInactive / 连接异常）→ N2 故障路径
            // （onFault → migrateToImmediate 迁移引擎），不再直降 IDLE 静默卡死。主动断开
            // 不触发：onDisconnect 先置 outbound=null 再 close，且 OutboundConnection.closed
            // 抑制 channelInactive/exceptionCaught 的 onError 回调。
            LOGGER.warn("Hassium: outbound error while ACTIVE — fault trigger (migrate): {}", cause.toString());
            onFault();
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
            if (LOGGER.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder();
                int n = Math.min(payload.readableBytes(), 16);
                for (int i = 0; i < n; i++) {
                    sb.append(String.format("%02X ", payload.getByte(payload.readerIndex() + i) & 0xFF));
                }
                LOGGER.debug("Hassium: T6DBG onS2CPayload size={} head={}", payload.readableBytes(), sb);
            }
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
            LOGGER.error("Hassium: S2C payload decode failed (payloadSize={})", payload.readableBytes(), t);
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
                // review-fix: T1-66 空闲窗口判定移除——区块 hash 活动仅消费方为已删检测器，
                // 收口只做元数据分发（增量未收敛信号不再接线）
                ClientMetadataHandler.handleChunkHashPacket(
                        (io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket) hp.packet());
            }
            case SECTION_DELTA -> ShadowLightCompute.submitDelta(
                    (io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket) hp.packet());
            case LIGHT_DELTA -> {
                io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket light =
                        (io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket) hp.packet();
                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightDeltaReceived(light.entries().size());
                LOGGER.debug("Hassium: LightDelta {} entries (shadow light invalidation)",
                        light.entries().size());
                // T6 接线：增量算光入口。shadow 清掉对应 section 的光（含变空 section）→
                // 重算收敛 → 以官方 ClientboundLightUpdatePacket 回传客户端，杜绝
                // 增量光照被丢弃后客户端保持旧光/黑光。
                io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submitLightDelta(light);
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
     * outbound PACKET_C2S 帧。返回 {@code true} = 已消费（MixinConnection 应取消原版发送——
     * 原版连接为壳，C2S 全走网关；掉线期丢弃同样返回 true，语义 = 已消费不发送不排队）；
     * {@code false} = 未路由（原版放行，降级）。
     * <p>
     * 分类（N1 掉线期丢弃，契约风险 4）：
     * <ul>
     *   <li>ACTIVE：正常路由（编码进 outbound）。入站静默 ≥ 生效静默超时
     *       （{@link MigrationEngine#isInboundSilent()}，与 T3 故障判定同源同参）时链路已失效，
     *       继续编码 = 帧在 TCP 缓冲排队 → 恢复后重放风暴 → 直接丢弃（故障前丢弃窗口）。</li>
     *   <li>CONNECTING/HANDSHAKING：正常连接建立期 → 原版直连兜底（登录期不可丢），返回 false。</li>
     *   <li>IDLE/MIGRATING：掉线期（onError 后 / 切换窗口）→ 已消费丢弃，返回 true——
     *       破坏/放置/交互/移动等 PLAY C2S 不排队不重放；keep-alive 壳保活与登录/配置中继
     *       （isConfigPacket/isLoginPacket 例外列表）在 MixinConnection 层先行旁路，不受影响。</li>
     * </ul>
     * 其余未路由情形：outbound 未开、无编码器、编码失败（未知/自定义包）。
     * 计数 {@link #c2sRoutedCount()} 每次调用 +1（可验证）。
     */
    public boolean routeC2S(Packet<?> packet) {
#if MC_VER >= MC_1_20_2
        // 配置阶段终包兜底：handleConfigurationFinished 先换 PLAY 监听器再 send(FinishConfiguration)，
        // mixin 的监听器判定已失真，这里按包类型兜底——必须原版发送（vanilla 依赖该包编码时
        // ProtocolSwapHandler 重置 outbound 管线；cancel 会导致 outbound 停在 encoder，
        // setupOutboundProtocol(PLAY) 任务写入未配置管线崩溃 UnsupportedOperationException）。
        // 其余配置阶段包由 mixin 配置分支正确分发到 relayConfigPacket，无需在此兜底。
        if (packet instanceof net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket) {
            return false;
        }
#endif
        c2sRouted.incrementAndGet();
        NetworkCoreState st = state.get();
        if (st != NetworkCoreState.ACTIVE) {
            // 正常连接建立期：原版直连兜底（不取消原版发送）
            if (st == NetworkCoreState.CONNECTING || st == NetworkCoreState.HANDSHAKING) {
                LOGGER.debug("Hassium: routeC2S {} passthrough (state={}, count={})",
                        packet.getClass().getSimpleName(), st, c2sRouted.get());
                return false;
            }
            // 掉线期（IDLE/MIGRATING）：已消费丢弃——MixinConnection cancel 原版发送，不排队不重放
            LOGGER.debug("Hassium: routeC2S {} dropped (state={}, 掉线期已消费, count={})",
                    packet.getClass().getSimpleName(), st, c2sRouted.get());
            return true;
        }
        // ACTIVE 但入站静默（心跳无回显 ≥ 静默超时，fault 判定窗口内）：链路失效即丢弃期
        if (migration.isInboundSilent()) {
            LOGGER.debug("Hassium: routeC2S {} dropped (inbound silent, count={})",
                    packet.getClass().getSimpleName(), c2sRouted.get());
            return true;
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
        // 展开 bundle（对称 routeS2C）：ServerboundBundlePacket 无独立协议 id
        // （<1.20.5 分隔机制 getPacketId=-1），直接 encode 会产出 vanillaId=-1 坏帧
        // 发给主控。逐子包递归路由，语义等价（丢渲染期原子性）。
        if (packet instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
            for (Object sub : bundle.subPackets()) {
                routeC2S((net.minecraft.network.protocol.Packet<?>) sub);
            }
            return true;
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

    /**
     * 发送客户端最终 authoritative apply 的 delivery ACK；未连通时返回 {@code false}，调用方保留待确认项。
     */
    public boolean sendChunkApplyAck(ChunkApplyAck ack) {
        java.util.Objects.requireNonNull(ack, "ack");
        OutboundConnection oc = outbound;
        if (state.get() != NetworkCoreState.ACTIVE || oc == null || !oc.isOpen()) {
            io.github.limuqy.mc.hassium.utils.StallDiag.clientHz(
                    "ack send dropped state={} open={}",
                    state.get(), oc != null && oc.isOpen());
            return false;
        }
        oc.sendChunkApplyAck(ack);
        return true;
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
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundKeepAlivePacket) {
            // 1.20.2+ 登录期 keep-alive 响应（common 包；登录监听器实现 ServerCommonPacketListener）
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

    // ==================== M3 仅网关登录（主连接失效恢复） ====================

    /**
     * M3：捕获最近一次原版连接意图（MixinConnectScreen.startConnecting HEAD，主线程）。
     * 原版连接失败（DisconnectedScreen 拦截）时决策：store 命中 → 仅网关登录。
     */
    public void captureConnectIntent(net.minecraft.client.multiplayer.ServerData serverData,
                                     net.minecraft.client.gui.screens.Screen parentScreen) {
        this.gatewayOnlyServerData = serverData;
        this.gatewayOnlyParentScreen = parentScreen;
        this.gatewayOnlyAttempted = false;
        // 连服意图即投机启动影子（与 vanilla 连接/握手并行；WorldLoader 重叠 login）
        try {
            io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper.startShadowIfConfigured(serverData);
        } catch (Throwable t) {
            LOGGER.debug("Hassium: early shadow start on connect intent skipped", t);
        }
    }

    /**
     * M3 失败决策（MixinMinecraft.setScreen 拦截 DisconnectedScreen → ConnectScreen 时
     * 调用，主线程）：store 命中且本意图未尝试过 → 启动仅网关登录会话并吞掉原版失败界面。
     * <p>
     * 放行原版失败界面的条件：已在仅网关登录中（由调用方先行拦截）之外——net.enabled=false、
     * 无连接意图、ServerData.ip 为空、store 未命中/不可用、本意图已尝试过。
     *
     * @return true = 已接管（调用方应取消原版 setScreen）
     */
    public boolean tryStartGatewayOnlyLogin(Minecraft mc) {
        try {
            if (gatewayOnlyLogin != null) {
                return true; // 已在仅网关登录中：吞掉后续失败界面，由会话收尾
            }
            if (gatewayOnlyAttempted) {
                return false; // 本连接意图已尝试过仅网关登录：原版失败界面
            }
            if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
                return false;
            }
            net.minecraft.client.multiplayer.ServerData sd = gatewayOnlyServerData;
            if (sd == null || sd.ip == null || sd.ip.isBlank()) {
                return false;
            }
            List<HassiumConfig.ReachableEndpoint> endpoints = lookupStoreEndpoints(sd.ip);
            if (endpoints.isEmpty()) {
                return false;
            }
            gatewayOnlyAttempted = true;
            GatewayOnlyLogin session = new GatewayOnlyLogin(this, mc, sd, gatewayOnlyParentScreen, endpoints);
            gatewayOnlyLogin = session;
            session.start();
            LOGGER.info("Hassium: gateway-only login started for {} ({} endpoint(s))", sd.ip, endpoints.size());
            return true;
        } catch (Throwable t) {
            LOGGER.error("Hassium: gateway-only login start aborted", t);
            gatewayOnlyLogin = null;
            return false;
        }
    }

    private List<HassiumConfig.ReachableEndpoint> lookupStoreEndpoints(String mainAddress) {
        try {
            Optional<ClientEndpointStore.Entry> entry = endpointStore().lookup(mainAddress);
            if (entry.isPresent() && !entry.get().endpoints().isEmpty()) {
                return entry.get().endpoints();
            }
        } catch (Throwable t) {
            LOGGER.warn("Hassium: gateway-only endpoint store lookup failed for {}", mainAddress, t);
        }
        return List.of();
    }

    /** 是否处于仅网关登录模式（登录期 + PLAY 期——PLAY keep-alive 无壳路径依赖）。 */
    public boolean isGatewayOnlyLogin() {
        return gatewayOnlyLogin != null;
    }

    /** 连接屏离开是否 = 用户取消（回父屏）；登录成功 setScreen(ReceivingLevelScreen) 非父屏 → false。 */
    public boolean isGatewayOnlyCancelTarget(net.minecraft.client.gui.screens.Screen screen) {
        GatewayOnlyLogin session = gatewayOnlyLogin;
        return session != null && session.isCancelTarget(screen);
    }

    /** 仅网关登录当前监听器（本地 Connection 监听器：登录期 = 登录监听器；
     *  handleGameProfile 后 = ClientPacketListener；会话未激活 → null）。 */
    public PacketListener gatewayOnlyLoginListener() {
        GatewayOnlyLogin session = gatewayOnlyLogin;
        return session == null ? null : session.listener();
    }

    /** 仅网关登录期出现原版失败界面（本地连接登录期断开）→ 会话收尾（取断开原因）。 */
    public void notifyGatewayOnlyDisconnect() {
        GatewayOnlyLogin session = gatewayOnlyLogin;
        if (session != null) {
            session.onLoginDisconnect();
        }
    }

    /** 连接屏取消（MixinConnectScreen.onClose HEAD）→ 会话静默收尾。 */
    public void notifyGatewayOnlyCancel() {
        GatewayOnlyLogin session = gatewayOnlyLogin;
        if (session != null) {
            session.onCancel();
        }
    }

    /** 仅网关登录会话结束（abort/静默收尾）。成功路径保留字段作模式标记（onDisconnect 清）。 */
    void notifyGatewayOnlySessionEnded() {
        gatewayOnlyLogin = null;
    }

    /** 仅网关登录收尾：关闭当前 outbound → IDLE（失败重试路径的 outbound 已由
     *  {@link #handleGatewayOnlyFailure()} 关闭，此处幂等）。 */
    void closeGatewayOnlyOutbound() {
        OutboundConnection oc = outbound;
        outbound = null;
        if (oc != null) {
            oc.close();
        }
        transitionTo(NetworkCoreState.IDLE);
    }

    // ==================== 迁移引擎（T8：Sink + 配置透传） ====================

    /** 故障触发（引擎心跳超时）：立即切换；无目标端点 → 降级 IDLE。 */
    @Override
    public void onFault() {
        NetworkCoreState st = state.get();
        // M3 仅网关登录期握手故障：重试下一 store 端点（不降级原版直连）
        if (handleGatewayOnlyFailure()) {
            LOGGER.warn("Hassium: fault trigger during gateway-only login (state={}) — retry next endpoint", st);
            return;
        }
        // A-M2: 握手期故障（CONNECTING/HANDSHAKING）→ N1 兜底：断 outbound + 回 IDLE
        // （原版直连兜底；修复静默/读超时故障在握手期被 ACTIVE-only 守卫吞掉 → 永久卡
        // HANDSHAKING）。MIGRATING/IDLE 保持现有迁移语义（忽略）。
        if (st == NetworkCoreState.CONNECTING || st == NetworkCoreState.HANDSHAKING) {
            LOGGER.warn("Hassium: fault trigger during handshake (state={}) — "
                    + "fall back to vanilla direct connection (N1)", st);
            OutboundConnection oc = outbound;
            outbound = null;
            if (oc != null) {
                oc.close();
            }
            transitionTo(NetworkCoreState.IDLE);
            return;
        }
        if (st != NetworkCoreState.ACTIVE) {
            LOGGER.debug("Hassium: fault trigger ignored (state={})", st);
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
     * lightComputeSupported 值源 = config.isHassiumEngineEnabled()（与
     * {@link HandshakeCodec.ClientRequestOptions#defaults()} 的 engineEnabled 同源；
     * 主控据此开剥光 gate）。
     * 环境不可用（无玩家/无 profile）时返回可安全发送的最小尾（playerId=null，主控
     * 回退登录桥路径）。
     */
    static HandshakeStateTail.C2S buildAutoTail() {
        return new HandshakeStateTail.C2S(clientPlayerState(), false, null, clientPlayerId(),
                HassiumConfigService.getInstance().isHassiumEngineEnabled());
    }

    // ==================== 状态机 ====================

    public NetworkCoreState state() {
        return state.get();
    }

    /** CAS 转移；成功返回 true 并日志（含距上次转移的耗时 delta，T0b 诊断）。 */
    public boolean transition(NetworkCoreState from, NetworkCoreState to) {
        if (state.compareAndSet(from, to)) {
            long now = System.currentTimeMillis();
            long deltaMs = stateEnteredAtMs > 0 ? now - stateEnteredAtMs : -1L;
            stateEnteredAtMs = now;
            recordStageEnter(to, now);
            LOGGER.info("Hassium: NetworkCore state {} -> {}{}", from, to,
                    deltaMs >= 0 ? " (+" + deltaMs + "ms)" : "");
            return true;
        }
        return false;
    }

    private void transitionTo(NetworkCoreState to) {
        NetworkCoreState prev = state.getAndSet(to);
        if (prev != to) {
            long now = System.currentTimeMillis();
            long deltaMs = stateEnteredAtMs > 0 ? now - stateEnteredAtMs : -1L;
            stateEnteredAtMs = now;
            recordStageEnter(to, now);
            LOGGER.info("Hassium: NetworkCore state {} -> {}{}", prev, to,
                    deltaMs >= 0 ? " (+" + deltaMs + "ms)" : "");
        }
    }

    /** T0b 诊断：记录握手各阶段进入时刻（仅 CONNECTING/HANDSHAKING；ACTIVE 累计在 onHandshakeAccepted）。 */
    private void recordStageEnter(NetworkCoreState to, long now) {
        if (to == NetworkCoreState.CONNECTING) {
            connectingAtMs = now;
        } else if (to == NetworkCoreState.HANDSHAKING) {
            handshakingAtMs = now;
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

    /** 最近一次 gateway_info（bootstrap 下发；仅网关登录取鉴权 token 用）。 */
    public GatewayInfoCodec.GatewayInfo lastGatewayInfo() {
        return lastGatewayInfo;
    }

    /** gateway_info 下发的鉴权 token；未收到 → 空串（不鉴权）。 */
    public String bootstrapAuthToken() {
        GatewayInfoCodec.GatewayInfo info = lastGatewayInfo;
        return info != null && info.authToken() != null ? info.authToken() : "";
    }

    // ==================== 内部 ====================

    /**
     * 尽力自动连接当前登录服务器（主控端点/鉴权仅信 gateway_info 下发）：
     * <ol>
     *   <li><b>gateway_info 已到</b>：连下发端点[0] + 下发 authToken</li>
     *   <li><b>探测兜底</b>：ServerData host + {@link GatewayPlayerBridge#DEFAULT_GATEWAY_PORT}
     *       （25566；禁连 vanilla 端口）。无鉴权 token（等 gateway_info 或服务端未开鉴权）</li>
     * </ol>
     * gateway_info 未到时事件驱动：onGatewayInfo 到达后自然 connect。
     * net.enabled=false 时全部跳过。失败仅告警。
     */
    private void autoConnect() {
        try {
            HassiumConfigService config = HassiumConfigService.getInstance();
            if (!config.isNetworkCompressionEnabled()) {
                LOGGER.debug("Hassium: NetworkCore auto-connect skipped (net.enabled=false)");
                return;
            }
            // ① gateway_info 已到 → 下发端点 + 下发 token
            GatewayInfoCodec.GatewayInfo info = lastGatewayInfo;
            if (info != null && !info.endpoints().isEmpty()) {
                GatewayInfoCodec.Endpoint first = info.endpoints().get(0);
                connect(first.host(), first.port(), buildAutoTail(), info.authToken());
                return;
            }
            // ② 探测兜底：ServerData host + 网关默认端口（禁连 vanilla 端口）
            String host = serverDataHost();
            if (host != null && !host.isBlank()) {
                connect(host, GatewayPlayerBridge.DEFAULT_GATEWAY_PORT, buildAutoTail(), "");
            }
        } catch (Throwable t) {
            LOGGER.debug("Hassium: NetworkCore auto-connect skipped", t);
        }
    }

    /** ServerData 主机名（剥掉尾部 ":端口"；无 ServerData → null）。探测兜底专用。 */
    private static String serverDataHost() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null || mc.getConnection().getServerData() == null) {
            return null;
        }
        String ip = mc.getConnection().getServerData().ip;
        if (ip == null || ip.isBlank()) {
            return null;
        }
        int colon = ip.lastIndexOf(':');
        if (colon > 0) {
            try {
                Integer.parseInt(ip.substring(colon + 1));
                return ip.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // IPv6 字面量或非数字后缀：整体按 host 处理
            }
        }
        return ip;
    }
}
