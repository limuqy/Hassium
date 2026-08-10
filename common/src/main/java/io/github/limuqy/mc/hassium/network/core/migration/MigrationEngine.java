package io.github.limuqy.mc.hassium.network.core.migration;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.config.MigrationPolicyConfig;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.ResumeTicket;
import io.github.limuqy.mc.hassium.network.ServerLoadReporter;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.OutboundConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * L1 迁移引擎（REQ C 节，骨架）：触发判定 + 迁移编排 + 预热 + 空闲窗口。
 *
 * <p><b>触发</b>：
 * <ul>
 *   <li>故障：outbound 入站静默超时（{@link MigrationPolicy#resolvedSilentTimeoutMs()}，
 *       默认 10s（N2 快速失效 ≤15s）；silentTimeout 未配置时回退既有
 *       {@code master.migrationFaultTimeoutMs} 语义）→ {@link Sink#onFault()}。
 *       读超时（Netty IdleStateHandler）同走 fault 路径。</li>
 *   <li>策略：{@link ServerLoadReporter.ServerLoadReport} → {@link #evaluatePolicy}
 *       （TPS 阈值 / 负载均值阈值 / 维护窗口）→ {@link Sink#onPolicyTrigger}。
 *       负载上报到客户端的线通道 = T10 CONFIG 帧（本任务不新增帧类型）；收到后调用
 *       {@link #onLoadReport} 即可。</li>
 *   <li>演练：{@link Sink} 侧手动调用迁移入口（NetworkCore.migrateTo，命令/API 接线
 *       为后续波）。</li>
 * </ul>
 *
 * <p><b>编排</b>：{@link #prewarm}（策略路径）→ 目标主控会话先建（PrewarmSession 携
 * 续流票据握手 → B 侧物化 + resyncTrackedChunks）→ {@link #takePrewarm} 接管；
 * 故障路径不预热，直接 {@link NetworkCore#migrateToImmediate}。票据 epoch 进程生命周期
 * 单调递增（各主控 validator 表跨会话不清理，恒递增防重放）。
 *
 * <p><b>UDP 会话迁移决策（记录）</b>：帧连接即控制连接（T12 udpTail 恒
 * {@code udpSupported=false}、epoch=0），beginControlConnection 不参与；UDP 数据面
 * 迁移归后续波。T7 交接的「beginControlConnection epoch 并入 validator」在网关形态下
 * 不适用（不触发）。
 *
 * <p>纯逻辑（无 MC 依赖）：位置/玩家身份经 {@link #setPlayerStateSource} /
 * {@link #setPlayerIdSource} 注入；时钟经 {@link #setClock}（测试）注入；
 * 连接经 {@link #setConnectionFactory}（测试）注入。心跳由 {@link #start} 的守护线程
 * 周期 tick（测试直接 {@link #tick} 手动驱动，不 start）。
 */
public final class MigrationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/MigrationEngine");

    /** 迁移动作去向（NetworkCore 实现）。 */
    public interface Sink {
        /** 心跳超时（故障）：应立即切换到下一个端点；无端点时实现方降级（关连接 → IDLE）。 */
        void onFault();

        /** 负载/维护窗口触发（策略）：预热感知迁移。 */
        void onPolicyTrigger(String reason);
    }

    /** 默认连接工厂（真实 NIO connect）。 */
    private static final PrewarmSession.OutboundConnectionFactory REAL_FACTORY =
            (host, port, options, tail, listener) -> OutboundConnection.connect(host, port, options, listener, tail);

    private volatile MigrationPolicy policy = MigrationPolicy.DEFAULT;
    private final CopyOnWriteArrayList<MigrationEndpoint> targetEndpoints = new CopyOnWriteArrayList<>();
    private volatile MigrationEndpoint currentEndpoint;
    private volatile Sink sink;
    private volatile PrewarmSession.OutboundConnectionFactory connectionFactory = REAL_FACTORY;
    private volatile Supplier<PlayerStateReport> playerStateSource;
    private volatile Supplier<UUID> playerIdSource;
    private volatile LongSupplier clockMs = System::currentTimeMillis;
    private volatile PrewarmSession prewarm;
    private volatile IdleWindowDetector idleDetector;

    /** 续流票据 epoch：进程生命周期单调递增（1 起；onLogin 不重置——各主控 validator 表跨会话持久）。 */
    private final AtomicLong epochCounter = new AtomicLong();

    // 续流票据 epoch 持久化（T2 B-M2）：重启后从 hassium_cache/<serverId>/resume-epoch.json
    // 加载继续递增，避免 epoch 归零 → 服务端防重放拒收 → 续流永久降级。
    private volatile Path epochStateFile;
    private boolean epochStateLoaded;
    private long lastPersistedEpoch;
    private final Object epochPersistLock = new Object();

    // 心跳监测（tick 线程）
    private volatile OutboundConnection heartbeatTarget;
    private final AtomicLong lastHeartbeatSentMs = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastInboundMs = new AtomicLong(Long.MIN_VALUE);
    private final AtomicBoolean faultReported = new AtomicBoolean(false);

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ScheduledExecutorService executor;

    // 观测计数
    private final AtomicLong migrationsStarted = new AtomicLong();
    private final AtomicLong faultsDetected = new AtomicLong();
    private final AtomicLong prewarmsStarted = new AtomicLong();
    private final AtomicLong prewarmsCompleted = new AtomicLong();
    private final AtomicLong loadTriggers = new AtomicLong();

    // ==================== 配置 ====================

    public void setPolicy(MigrationPolicy policy) {
        this.policy = policy != null ? policy : MigrationPolicy.DEFAULT;
    }

    public MigrationPolicy policy() {
        return policy;
    }

    /**
     * 配置接线辅助（B2 全链接线）：policy 中仍为默认值的字段用配置快照覆盖（程序化
     * {@link #setPolicy} 优先）；silentTimeout/faultTimeout 的兼容回退见
     * {@link MigrationPolicy#resolvedSilentTimeoutMs}。
     */
    public void applyMigrationPolicyFromConfig(MigrationPolicyConfig config) {
        MigrationPolicy p = policy;
        MigrationPolicy d = MigrationPolicy.DEFAULT;
        double minTps = p.minTps() != d.minTps() ? p.minTps() : config.minTps();
        double maxLoadAverage = p.maxLoadAverage() != d.maxLoadAverage()
                ? p.maxLoadAverage() : config.maxLoadAverage();
        String maintenanceWindow = !d.maintenanceWindow().equals(p.maintenanceWindow())
                ? p.maintenanceWindow() : config.maintenanceWindow();
        long heartbeatIntervalMs = p.heartbeatIntervalMs() != d.heartbeatIntervalMs()
                ? p.heartbeatIntervalMs() : config.heartbeatIntervalMs();
        long silentTimeoutMs = p.silentTimeoutMs() != d.silentTimeoutMs()
                ? p.silentTimeoutMs() : config.silentTimeoutMs();
        long faultTimeoutMs = p.faultTimeoutMs() != d.faultTimeoutMs()
                ? p.faultTimeoutMs() : config.faultTimeoutMs();
        long idleWindowMs = p.idleWindowMs() != d.idleWindowMs()
                ? p.idleWindowMs() : config.idleWindowMs();
        // idleMoveThresholdBps 无独立配置键：保持 policy 现值（默认 0.5）
        setPolicy(new MigrationPolicy(minTps, maxLoadAverage, maintenanceWindow, heartbeatIntervalMs,
                silentTimeoutMs, faultTimeoutMs, idleWindowMs, p.idleMoveThresholdBps(), p.prewarmEnabled()));
    }

    /** 候选目标端点（迁移环形推进）。 */
    public void setTargetEndpoints(List<MigrationEndpoint> endpoints) {
        targetEndpoints.clear();
        if (endpoints != null) {
            targetEndpoints.addAll(endpoints);
        }
    }

    public List<MigrationEndpoint> targetEndpoints() {
        return List.copyOf(targetEndpoints);
    }

    /** 记录当前服务端点（迁移目标选择排除自身）。 */
    public void noteCurrentEndpoint(MigrationEndpoint endpoint) {
        currentEndpoint = endpoint;
    }

    /** 环形选取下一个目标端点（排除当前；无候选返回 null）。 */
    public MigrationEndpoint nextEndpoint() {
        if (targetEndpoints.isEmpty()) {
            return null;
        }
        MigrationEndpoint current = currentEndpoint;
        if (targetEndpoints.size() == 1) {
            MigrationEndpoint only = targetEndpoints.get(0);
            return only.equals(current) ? null : only;
        }
        int idx = current == null ? -1 : targetEndpoints.indexOf(current);
        for (int i = 1; i <= targetEndpoints.size(); i++) {
            MigrationEndpoint candidate = targetEndpoints.get((idx + i) % targetEndpoints.size());
            if (!candidate.equals(current)) {
                return candidate;
            }
        }
        return null;
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    /** 连接工厂（默认真实 NIO；测试注入 EmbeddedChannel 缝；null 重置默认）。 */
    public void setConnectionFactory(PrewarmSession.OutboundConnectionFactory factory) {
        this.connectionFactory = factory != null ? factory : REAL_FACTORY;
    }

    /** 经连接工厂创建 outbound（NetworkCore 使用；默认真实 NIO，测试注入缝）。 */
    public OutboundConnection createConnection(String host, int port,
                                               HandshakeCodec.ClientRequestOptions options,
                                               HandshakeStateTail.C2S tail,
                                               OutboundConnection.Listener listener) {
        return connectionFactory.create(host, port, options, tail, listener);
    }

    public void setPlayerStateSource(Supplier<PlayerStateReport> source) {
        this.playerStateSource = source;
    }

    public void setPlayerIdSource(Supplier<UUID> source) {
        this.playerIdSource = source;
    }

    /** 时钟注入（测试/观测；默认 System::currentTimeMillis）。 */
    public void setClock(LongSupplier clock) {
        this.clockMs = clock != null ? clock : System::currentTimeMillis;
    }

    // ==================== 生命周期（NetworkCore.onLogin/onDisconnect） ====================

    /** 启动心跳监测线程（守护；幂等）。测试不调用——直接 {@link #tick} 手动驱动。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Hassium-MigrationEngine");
            t.setDaemon(true);
            return t;
        });
        executor = svc;
        svc.scheduleWithFixedDelay(() -> {
            try {
                tick(clockMs.getAsLong());
            } catch (Throwable t) {
                LOGGER.error("Hassium: migration engine tick failed", t);
            }
        }, 1_000L, 1_000L, TimeUnit.MILLISECONDS);
        LOGGER.info("Hassium: migration engine started (heartbeat={}ms, silentTimeout={}ms, idleWindow={}ms)",
                policy.heartbeatIntervalMs(), policy.resolvedSilentTimeoutMs(), policy.idleWindowMs());
    }

    /** 停止心跳监测（幂等）。 */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService svc = executor;
        executor = null;
        if (svc != null) {
            svc.shutdownNow();
        }
        heartbeatTarget = null;
        PrewarmSession p = prewarm;
        prewarm = null;
        if (p != null) {
            p.close();
        }
        LOGGER.info("Hassium: migration engine stopped");
    }

    // ==================== 心跳 / 故障触发 ====================

    /**
     * 心跳 tick（start 的守护线程周期调用；测试手动驱动）。
     * 1) 空闲窗口采样；2) 到点发 HEARTBEAT；3) 入站静默 ≥ 生效静默超时 → 故障触发。
     */
    public void tick(long nowMs) {
        sampleIdleWindow(nowMs);
        MigrationPolicy p = policy;
        OutboundConnection oc = heartbeatTarget;
        if (oc == null || !oc.isOpen()) {
            return;
        }
        long lastSent = lastHeartbeatSentMs.get();
        if (lastSent == Long.MIN_VALUE || nowMs - lastSent >= p.heartbeatIntervalMs()) {
            if (lastHeartbeatSentMs.compareAndSet(lastSent, nowMs)) {
                try {
                    oc.sendHeartbeat();
                } catch (Throwable t) {
                    LOGGER.warn("Hassium: heartbeat send failed", t);
                }
            }
        }
        long silentTimeout = p.resolvedSilentTimeoutMs();
        long lastIn = lastInboundMs.get();
        if (lastIn != Long.MIN_VALUE && nowMs - lastIn >= silentTimeout
                && faultReported.compareAndSet(false, true)) {
            faultsDetected.incrementAndGet();
            LOGGER.warn("[FAILOVER] outbound silent for {}ms (silentTimeout={}ms) — fault trigger",
                    nowMs - lastIn, silentTimeout);
            Sink s = sink;
            if (s != null) {
                try {
                    s.onFault();
                } catch (Throwable t) {
                    LOGGER.error("Hassium: fault sink failed", t);
                }
            }
        }
    }

    /** 入站活动（任意帧；NetworkCore 经 OutboundConnection.setInboundActivityListener 接线）。 */
    public void noteInboundActivity() {
        long now = clockMs.getAsLong();
        lastInboundMs.set(now);
        faultReported.set(false);
    }

    /**
     * 入站静默判定（T6 掉线期丢弃窗口，与 {@link #tick} 故障判定同源同参——
     * 均用 {@link MigrationPolicy#resolvedSilentTimeoutMs()}）：最近入站活动距今 ≥
     * 生效静默超时视为链路已失效——此时继续编码 C2S 只会让帧在 TCP 缓冲排队
     * （主控恢复后重放风暴），故 NetworkCore.routeC2S 在静默期直接丢弃。
     * 无入站记录（连接建立期/引擎未绑定）恒 false；不触发任何状态变更（fault 仍由 tick 驱动）。
     */
    public boolean isInboundSilent() {
        long lastIn = lastInboundMs.get();
        if (lastIn == Long.MIN_VALUE) {
            return false;
        }
        return clockMs.getAsLong() - lastIn >= policy.resolvedSilentTimeoutMs();
    }

    /** outbound 建立（握手接受/预热接管后调用）：重置心跳计时 + 启用 Netty 读超时（fault 路径）。 */
    public void bindHeartbeatTarget(OutboundConnection connection) {
        heartbeatTarget = connection;
        long now = clockMs.getAsLong();
        lastInboundMs.set(now);
        lastHeartbeatSentMs.set(now);
        faultReported.set(false);
        // N2 快速失效：TCP 层读超时（event loop 级，与应用层静默判定同阈值）→ fault 路径
        // （Sink.onFault → 迁移）。契约风险 5：读超时禁止直降 onError→IDLE。
        connection.enableReadTimeout(policy.resolvedSilentTimeoutMs(), this::onTransportReadTimeout);
    }

    /** Netty 读超时 → 故障路径（与 tick 静默判定同去重标志；onFault → 迁移而非踢下线）。 */
    private void onTransportReadTimeout() {
        if (!faultReported.compareAndSet(false, true)) {
            return;
        }
        faultsDetected.incrementAndGet();
        LOGGER.warn("[FAILOVER] outbound read timeout (Netty idle) — fault trigger");
        Sink s = sink;
        if (s != null) {
            try {
                s.onFault();
            } catch (Throwable t) {
                LOGGER.error("Hassium: fault sink failed", t);
            }
        }
    }

    // ==================== 策略触发 ====================

    /** 策略判定结果。 */
    public record Decision(boolean migrate, String reason) {
        public static Decision migrate(String reason) {
            return new Decision(true, reason);
        }

        public static Decision none(String reason) {
            return new Decision(false, reason);
        }
    }

    /** 负载阈值 + 维护窗口判定（纯函数；ServerLoadReporter 报告 → 是否迁移）。 */
    public Decision evaluatePolicy(ServerLoadReporter.ServerLoadReport report) {
        MigrationPolicy p = policy;
        if (report == null) {
            return Decision.none("no load report");
        }
        long now = clockMs.getAsLong();
        if (p.inMaintenanceWindow(now)) {
            return Decision.migrate("maintenance window " + p.maintenanceWindow());
        }
        if (report.tps() < p.minTps()) {
            return Decision.migrate(String.format("tps %.1f < minTps %.1f", report.tps(), p.minTps()));
        }
        if (report.systemLoadAverage() >= 0.0 && report.systemLoadAverage() > p.maxLoadAverage()) {
            return Decision.migrate(String.format("loadAverage %.2f > max %.2f",
                    report.systemLoadAverage(), p.maxLoadAverage()));
        }
        return Decision.none("load within thresholds");
    }

    /**
     * 负载报告入口（线通道收到报告后调用；T10 CONFIG 帧接线）。
     * 判定迁移 → {@link Sink#onPolicyTrigger}。
     */
    public void onLoadReport(ServerLoadReporter.ServerLoadReport report) {
        Decision decision = evaluatePolicy(report);
        if (!decision.migrate()) {
            return;
        }
        loadTriggers.incrementAndGet();
        LOGGER.info("[MIGRATE] policy trigger: {}", decision.reason());
        Sink s = sink;
        if (s != null) {
            try {
                s.onPolicyTrigger(decision.reason());
            } catch (Throwable t) {
                LOGGER.error("Hassium: policy sink failed", t);
            }
        }
    }

    // ==================== 续流票据 / 预热 ====================

    /** 构造续流票据：playerId（注入源）+ 递增 epoch + 签发时间戳 + 共享密钥签名（T7 ResumeTicket）。 */
    public ResumeTicket createResumeTicket() {
        Supplier<UUID> src = playerIdSource;
        UUID playerId = src != null ? src.get() : null;
        if (playerId == null) {
            throw new IllegalStateException("playerId unavailable (playerIdSource not set or returned null)");
        }
        long epoch = nextResumeEpoch();
        long issuedAtMs = System.currentTimeMillis();
        return new ResumeTicket(playerId, epoch, issuedAtMs,
                ResumeTicket.sign(playerId, epoch, issuedAtMs, ResumeTicket.sharedKey()));
    }

    /**
     * 签发 epoch：首次签发前定位并加载持久化值（重启续流不降级），签发后同步落盘。
     * 内存计数器（AtomicLong）与落盘值（只写严格更大值，锁内）双重保证严格单调。
     */
    private long nextResumeEpoch() {
        if (!epochStateLoaded) {
            loadPersistedEpoch();
        }
        long epoch = epochCounter.incrementAndGet();
        persistEpoch(epoch);
        return epoch;
    }

    /** 启动加载：epochCounter 起点 = max(当前, 持久化值)；下次 incrementAndGet 自然 +1 继续用。幂等。 */
    public void loadPersistedEpoch() {
        synchronized (epochPersistLock) {
            if (epochStateLoaded) {
                return;
            }
            epochStateLoaded = true;
            if (epochStateFile == null) {
                resolveEpochStateFile();
            }
            final Path file = epochStateFile;
            if (file == null || !Files.isRegularFile(file)) {
                return;
            }
            try {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                long persisted = parseEpochJson(content);
                if (persisted > 0) {
                    epochCounter.accumulateAndGet(persisted, Math::max);
                    lastPersistedEpoch = persisted;
                    LOGGER.info("Hassium: resume ticket epoch restored to {} from {}", persisted, file);
                }
            } catch (Exception e) {
                LOGGER.warn("Hassium: resume ticket epoch load failed from {}: {}", file, e.toString());
            }
        }
    }

    /** 签发后落盘：只写严格更大的值（并发/乱序安全）；文件不可写仅告警，不阻断签发。 */
    private void persistEpoch(long epoch) {
        synchronized (epochPersistLock) {
            if (epoch <= lastPersistedEpoch) {
                return;
            }
            lastPersistedEpoch = epoch;
            final Path file = epochStateFile;
            if (file == null) {
                return;
            }
            try {
                atomicWrite(file, "{\"version\":1,\"epoch\":" + epoch + "}");
            } catch (Exception e) {
                LOGGER.warn("Hassium: resume ticket epoch persist failed to {}: {}", file, e.toString());
            }
        }
    }

    /** 定位持久化文件：与影子端缓存同目录 {@code hassium_cache/<serverId>/resume-epoch.json}。 */
    private void resolveEpochStateFile() {
        try {
            ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
            Path gameDir = pipeline.getGameDir();
            String serverId = pipeline.getServerId();
            if (gameDir != null && serverId != null) {
                epochStateFile = gameDir.resolve("hassium_cache").resolve(serverId).resolve("resume-epoch.json");
                LOGGER.info("Hassium: resume ticket epoch state file: {}", epochStateFile);
            }
        } catch (Throwable t) {
            // 定位失败保持禁用（进程内单调），不影响续流功能本身
        }
    }

    /** 解析本类写出的 epoch 文件：{"version":1,"epoch":<n>}；损坏返回 0（从头计数）。 */
    private static long parseEpochJson(String content) {
        if (content == null) {
            return 0L;
        }
        int key = content.indexOf("\"epoch\":");
        if (key < 0) {
            return 0L;
        }
        int start = key + "\"epoch\":".length();
        int end = content.indexOf('}', start);
        if (end < 0) {
            end = content.length();
        }
        try {
            return Long.parseLong(content.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 原子写：临时文件 + rename（ATOMIC_MOVE，不支持时回退 REPLACE_EXISTING）。 */
    private static void atomicWrite(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 构造握手续流状态尾（T7 HandshakeStateTail.C2S）：玩家状态 + resumeRequested +
     * 票据字节。resumeRequested=true 但票据构造失败（无玩家身份）→ 降级为不请求续流。
     */
    public HandshakeStateTail.C2S buildHandshakeTail(boolean resumeRequested) {
        Supplier<PlayerStateReport> src = playerStateSource;
        PlayerStateReport state = src != null ? src.get() : null;
        if (state == null) {
            state = PlayerStateReport.absent();
        }
        byte[] ticket = null;
        UUID playerId = null;
        if (resumeRequested) {
            try {
                ResumeTicket t = createResumeTicket();
                ticket = t.encode();
                playerId = t.playerId();
            } catch (IllegalStateException e) {
                LOGGER.warn("Hassium: resume ticket unavailable — handshake without resume: {}", e.toString());
                resumeRequested = false;
            }
        } else {
            Supplier<UUID> idSrc = playerIdSource;
            playerId = idSrc != null ? idSrc.get() : null;
        }
        // T10：C2S 尾追加玩家 UUID 字段（标准流程握手附着；续流路径 = 票据身份）
        // A7：lightComputeSupported 值源 = config.isHassiumEngineEnabled()（与
        // ClientRequestOptions.engineEnabled() 同源；主控据此开剥光 gate）
        return new HandshakeStateTail.C2S(state, resumeRequested, ticket, playerId,
                HassiumConfigService.getInstance().isHassiumEngineEnabled());
    }

    /**
     * 启动预热（策略路径）：连接目标主控 + 续流票据握手（B 侧物化 + resyncTrackedChunks
     * 预同步）。已在飞（同端点未 ready）→ 返回既有会话；不同端点/陈旧 → 关闭重建。
     * 完成后经 {@link #takePrewarm} 由迁移发起方接管。
     */
    public PrewarmSession prewarm(MigrationEndpoint endpoint, PrewarmSession.Callback callback) {
        PrewarmSession existing = prewarm;
        if (existing != null) {
            if (!existing.ready() && !existing.isTerminal() && existing.endpoint().equals(endpoint)) {
                LOGGER.debug("[PREWARM] already in flight to {}", endpoint);
                return existing;
            }
            existing.close();
        }
        HandshakeStateTail.C2S tail = buildHandshakeTail(true);
        prewarmsStarted.incrementAndGet();
        PrewarmSession session = PrewarmSession.create(endpoint, tail, callback, connectionFactory);
        prewarm = session;
        LOGGER.info("[PREWARM] connecting to {} (ticket epoch={})", endpoint, epochCounter.get());
        return session;
    }

    /** 取走已就绪的预热会话（迁移发起方接管；未就绪/不匹配 → null）。 */
    public PrewarmSession takePrewarm(MigrationEndpoint endpoint) {
        PrewarmSession s = prewarm;
        if (s != null && s.ready() && s.endpoint().equals(endpoint)) {
            prewarm = null;
            return s;
        }
        return null;
    }

    /** 同端点预热是否在飞（迁移发起方等待回调；终态会话不算在飞）。 */
    public boolean prewarmInFlight(MigrationEndpoint endpoint) {
        PrewarmSession s = prewarm;
        return s != null && !s.ready() && !s.isTerminal() && s.endpoint().equals(endpoint);
    }

    /** 清掉指定预热会话引用（身份守卫：仅当仍指向该会话）。 */
    public void clearPrewarm(PrewarmSession session) {
        if (prewarm == session) {
            prewarm = null;
        }
    }

    // ==================== 空闲窗口 ====================

    /** 空闲窗口判定（玩家静止 + 区块 hash 稳定；检测器未就绪 = 不判定 → true）。 */
    public boolean isIdleWindow() {
        IdleWindowDetector d = idleDetector;
        return d == null || d.isIdle();
    }

    /** 区块 hash 活动信号（入站 ChunkHashS2CPacket 等；NetworkCore dispatch 接线）。 */
    public void noteChunkHashActivity() {
        IdleWindowDetector d = idleDetector;
        if (d != null) {
            d.noteChunkHashActivity();
        }
    }

    private void sampleIdleWindow(long nowMs) {
        Supplier<PlayerStateReport> src = playerStateSource;
        if (src == null) {
            return;
        }
        MigrationPolicy p = policy;
        IdleWindowDetector d = idleDetector;
        // 空闲窗口参数随 policy 变化（B2：idleWindowMs/idleMoveThresholdBps 移入 policy）→ 重建检测器
        if (d == null || d.windowMs() != p.idleWindowMs()
                || d.moveThresholdBlocksPerSec() != p.idleMoveThresholdBps()) {
            d = new IdleWindowDetector(p.idleMoveThresholdBps(), p.idleWindowMs(), clockMs);
            idleDetector = d;
        }
        PlayerStateReport state = src.get();
        if (state != null && state.present()) {
            d.sample(state.x(), state.z());
        }
    }

    // ==================== 观测 ====================

    public long migrationsStarted() {
        return migrationsStarted.get();
    }

    public long faultsDetected() {
        return faultsDetected.get();
    }

    public long prewarmsStarted() {
        return prewarmsStarted.get();
    }

    public long prewarmsCompleted() {
        return prewarmsCompleted.get();
    }

    public long loadTriggers() {
        return loadTriggers.get();
    }

    public long currentEpoch() {
        return epochCounter.get();
    }

    /** 迁移计数（NetworkCore 发起时调用）。 */
    public void noteMigrationStarted() {
        migrationsStarted.incrementAndGet();
    }
}
