package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Task 9 — 控制面自动重连编排（plan §995-1003）。
 *
 * <p>职责：在 TCP 主控制连接断开后，按候选列表逐个 launch 新连接；协作的 launcher 走 vanilla
 * Fabric multiplayer connect 调用（依然在 launcher 实现）。一旦握手成功（新 S2C tail 收到）即
 * 进入 RECOVERED；任一候选失败 → {@link #onReconnectFailed(ControlEndpoint)} 尝试下一个；耗尽则
 * 走一次性 terminal finalize。
 *
 * <p>客户端单例 <b>不复用 {@link ClientRecoveryState}</b> —— 那个是 MixinMinecraft /
 * ClientLifecycleHelper 的 cross-cutting gate，使用时机和 reactor 不同步会破坏测试隔离。
 * orchestrator 维护自己的恢复标志：
 * <ul>
 *   <li>{@link #recovering} 是否在恢复中。</li>
 *   <li>{@link #connectionEpoch} 握手新轮的 epoch（默认 0 表示无恢复）。</li>
 *   <li>{@link #remaining} 尚未尝试或可重试的候选。</li>
 *   <li>{@link #current} 当前已 launch 的候选（onReconnectFailed 时移除）。</li>
 * </ul>
 *
 * <p>Thread-safe via synchronized methods — driven from Minecraft client main thread and
 * Fabric channel callbacks.
 */
public final class ControlReconnectOrchestrator {
    private static final Logger SMOKE_LOG = LoggerFactory.getLogger("HassiumSmokeTest");


    private final ControlReconnectLauncher launcher;
    /** bootstrap + advertised 合并后的候选，按 priority 降序（merge 时已 cap） */
    private final List<ControlEndpoint> candidates;
    private final ControlEndpointManager manager = new ControlEndpointManager();
    private final LongSupplier recoveryWindowMs;

    private boolean recovering = false;
    private long connectionEpoch = 0L;
    private boolean terminalFinalized = false;
    private int terminalFinalizations = 0;
    /** 当前 launch 在 flight 中的候选；onReconnectFailed 会以这个为参数被调用 */
    private ControlEndpoint current;
    private long recoveryStartedAtMs;
    private long recoveryDeadlineMs;

    public ControlReconnectOrchestrator(ControlReconnectLauncher launcher,
                                        List<ControlEndpoint> bootstrap,
                                        List<ControlEndpoint> advertised) {
        this(launcher, bootstrap, advertised,
                () -> HassiumConfigService.getInstance().getDataPlaneConfig().recoveryWindowMs());
    }

    ControlReconnectOrchestrator(ControlReconnectLauncher launcher,
                                 List<ControlEndpoint> bootstrap,
                                 List<ControlEndpoint> advertised,
                                 LongSupplier recoveryWindowMs) {
        this.launcher = Objects.requireNonNull(launcher);
        this.recoveryWindowMs = Objects.requireNonNull(recoveryWindowMs);
        this.candidates = new ArrayList<>();
        this.manager.mergeBootstrapAndAdvertised(bootstrap, advertised);
    }

    /** 测试便捷构造：直接给定合并后候选列表。 */
    static ControlReconnectOrchestrator forTest(ControlReconnectLauncher launcher,
                                                List<ControlEndpoint> candidates) {
        return forTest(launcher, candidates, HassiumConfig.DEFAULT.serverNetwork().dataPlane());
    }

    /** 测试便捷构造：以不可变数据面配置固定恢复窗口。 */
    static ControlReconnectOrchestrator forTest(ControlReconnectLauncher launcher,
                                                List<ControlEndpoint> candidates,
                                                HassiumConfig.DataPlaneConfig dataPlaneConfig) {
        ControlReconnectOrchestrator o = new ControlReconnectOrchestrator(
                launcher, candidates, List.of(), dataPlaneConfig::recoveryWindowMs);
        o.candidates.addAll(candidates.stream().filter(Objects::nonNull).toList());
        o.manager.mergeBootstrapAndAdvertised(candidates, List.of());
        return o;
    }

    /** 主控连接断开（通道 inactive 或 stall 触发 FailoverRequest 被拒）。立刻尝试下一候选。 */
    public synchronized void onPrimaryDisconnected(ControlEndpoint active, String reason) {
        if (terminalFinalized) {
            return; // 已经在 terminal；不再 launch
        }
        // 用 (active, advertised-candidates-merged) 把候选灌进 manager，bootstrap 替换为 active
        List<ControlEndpoint> bootstrap = new ArrayList<>();
        if (active != null) bootstrap.add(active);
        // candidates（构造时已有）作为 advertised
        manager.mergeBootstrapAndAdvertised(bootstrap, candidates);
        startRecoveryWindow();
        // 注意：startRecovery 内部 rebuildRemaining 会从 candidates 重建 remaining，
        // 所以 active 的剔除必须放在它之后才能生效。
        if (active != null) {
            manager.recordAttemptFailure(active);
        }
        recovering = true;
        connectionEpoch = nextEpoch();
        if (!launchNextCandidate()) {
            // 无剩余候选（含仅 active 已被剔除）：立刻 terminal，避免 recovering 悬挂导致
            // 下一次普通握手误报 FAILOVER_RECONNECT_OK，以及 stopUdp(keepLease) 空 bundle NPE。
            performTerminalFinalization();
        }
    }

    /** FailoverPermit：仅当对应本轮 epoch 且未过期时被认为有效 —— 不直接 launch（候选已 launch at begin）。 */
    public synchronized void onFailoverPermit(long epoch, long expiryMs) {
        if (!recovering) {
            return; // 没有 onPrimaryDisconnected 上下文 → 忽略
        }
        if (epoch != connectionEpoch) {
            return; // 不是本轮的 permit → 忽略
        }
        if (expiryMs <= System.currentTimeMillis()) {
            return; // 过期 permit → 忽略
        }
        // Permit 本身不直接 launch；候选已经在恢复开始时 launch。若 current 已失败但 permit
        // 仍在新轮存活 → candidate 已被 recordAttemptFailure 剔除，下一候选由下一 onReconnectFailed 链路触发。
    }

    /**
     * 新握手 S2C tail 收到时结束一轮真实恢复。
     *
     * @return {@code true} 仅表示本调用完成了已启动的 failover 恢复；首次连接返回 {@code false}。
     */
    public synchronized boolean onHandshakeAccepted() {
        if (!recovering) {
            return false;
        }
        recovering = false;
        terminalFinalized = false;
        // connectionEpoch 保持本轮，便于客户端据此 BindRequest 新一代 UDP
        SMOKE_LOG.info("HassiumSmokeTest:UDP_FAILOVER FAILOVER_RECONNECT_OK epoch={}", connectionEpoch);
        return true;
    }

    /** 一候选 reconnect 失败：移除并尝试下一个；若耗尽 → terminal finalize 一次。 */
    public synchronized void onReconnectFailed(ControlEndpoint endpoint) {
        if (terminalFinalized) {
            return;
        }
        if (endpoint != null) {
            manager.recordAttemptFailure(endpoint);
        }
        if (current != null && endpoint != null && current.coordinateKey().equals(endpoint.coordinateKey())) {
            current = null;
        }
        if (!tryLaunchNextCandidate()) {
            // 候选耗尽：terminal finalize exactly once
            performTerminalFinalization();
        }
    }

    public synchronized boolean isRecovering() {
        if (!recovering) return false;
        // 在恢复中 + 还有候选尚未失败 → 真 RECOVERING
        return manager.isRecoveryActive() || current != null;
    }

    /**
     * Task 9 — 客户端在 S2C 握手尾部收到时灌 advertised 控制候选。
     * orchestrator 持这些候选以便后续 onPrimaryDisconnected 起点选路；不在调用中直接 manager.merge，
     * 等到真正启动恢复才走瘦身 dedup + cap。
     */
    public synchronized void configureCandidates(List<ControlEndpoint> advertised) {
        this.candidates.clear();
        this.candidates.addAll(advertised);
    }

    /** 是否已有握手下发的热切候选（空则普通断开由 ClientSmokeTest/用户自行重连）。 */
    public synchronized boolean hasAdvertisedCandidates() {
        return !candidates.isEmpty();
    }

    public synchronized int terminalFinalizations() {
        return terminalFinalizations;
    }

    public synchronized long connectionEpoch() {
        return connectionEpoch;
    }

    public synchronized ControlEndpoint currentLaunchedEndpoint() {
        return current;
    }

    synchronized long recoveryStartedAtMs() {
        return recoveryStartedAtMs;
    }

    synchronized long recoveryDeadlineMs() {
        return recoveryDeadlineMs;
    }

    // ===== test injection =====

    /** 测试 hook：以指定 epoch + 起点 begin recovery，不经 onPrimaryDisconnected 路径。 */
    synchronized void beginRecoveryForTest(long epoch, ControlEndpoint originator) {
        recovering = true;
        connectionEpoch = epoch;
        List<ControlEndpoint> bootstrap = originator != null ? List.of(originator) : List.of();
        manager.mergeBootstrapAndAdvertised(bootstrap, candidates);
        startRecoveryWindow();
        if (originator != null) manager.recordAttemptFailure(originator);
        launchNextCandidate();
    }

    private void startRecoveryWindow() {
        manager.startRecovery(recoveryWindowMs.getAsLong());
        recoveryStartedAtMs = manager.recoveryStartedAtMs();
        recoveryDeadlineMs = manager.recoveryDeadlineMs();
    }

    // ===== internal =====

    private boolean tryLaunchNextCandidate() {
        return launchNextCandidate();
    }

    private boolean launchNextCandidate() {
        var next = manager.nextCandidate();
        if (next.isEmpty()) {
            return false;
        }
        ControlEndpoint target = next.get();
        current = target;
        launcher.connect(target, () -> onReconnectFailed(target));
        // launch 成功 → 候选保留在 remaining；失败时 recordAttemptFailure 在 onReconnectFailed 路径
        return true;
    }

    private void performTerminalFinalization() {
        if (terminalFinalized) {
            return;
        }
        terminalFinalized = true;
        recovering = false;
        terminalFinalizations++;
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
        SMOKE_LOG.info("HassiumSmokeTest:UDP_FAILOVER FAILOVER_TERMINAL_OK finalizations={}", terminalFinalizations);
    }

    /** 单调 + 的 epoch 候选：以 1 起步，0 reserved for "no recovery"。 */
    private static long nextEpoch() {
        // 简化：用 System.nanoTime 单调上取；运行中的 actual epoch 来自握手 S2C tail，
        // orchestrator 在 onHandshakeAccepted 之后让 client 重新读 S2C 给的 epoch。
        long now = System.nanoTime();
        if (now == 0L) return 1L;
        return now;
    }
}
