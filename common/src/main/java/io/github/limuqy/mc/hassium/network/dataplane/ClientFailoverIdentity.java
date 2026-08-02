package io.github.limuqy.mc.hassium.network.dataplane;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Main-thread client facade for persistent failover state and cache identity. */
public final class ClientFailoverIdentity {
    private static volatile ClientFailoverIdentity instance;

    /** L2 世界定格标志：F1/F2 在恢复窗口置位，F3（新世界 setLevel）/F8（terminal 回退）复位。 */
    private static volatile boolean freezeActive = false;

    public static boolean isFreezeActive() {
        return freezeActive;
    }

    public static void markFreezeActive(boolean active) {
        freezeActive = active;
    }

    /** L2 恢复会话渲染遮挡：从恢复启动到新世界正常渲染（ReceivingLevelScreen 移除）保持置位，
     * 期间过渡画面（ConnectScreen/ProgressScreen/ReceivingLevelScreen）不绘制，画面保持冻结世界
     * + HUD 浮层。置位在 orchestrator 恢复启动，清除在 MixinClientTick（新世界 screen==null）/
     * terminal finalize。 */
    private static volatile boolean recoverySessionActive = false;

    public static boolean isRecoverySessionActive() {
        return recoverySessionActive;
    }

    public static void markRecoverySession(boolean active) {
        recoverySessionActive = active;
    }

    /** 从断开的控制连接通道解析 active 端点（host/port，priority 0），供恢复轮转剔除；
     * 无 remoteAddress（LocalChannel/内存连接）时返回 null。
     * <p>host 必须用 {@code getHostAddress()}（IP 字面量）：{@code getHostString()} 在
     * Windows/Netty 上返回 DNS 主机名（如 localhost.sangfor.com.cn），与候选列表里的
     * 127.0.0.1 坐标不一致会导致 recordAttemptFailure 剔除失效、永远重试同一候选。 */
    public static ControlEndpoint activeEndpointFromChannel(io.netty.channel.Channel channel) {
        if (channel == null) {
            return null;
        }
        java.net.SocketAddress addr = channel.remoteAddress();
        if (addr instanceof java.net.InetSocketAddress isa) {
            return new ControlEndpoint(isa.getAddress().getHostAddress(), isa.getPort(), 0);
        }
        return null;
    }

    private final ClientFailoverEndpointStore store;
    private final ControlReconnectOrchestrator orchestrator;
    private String primaryAddress;
    private ControlEndpoint activeFallback;
    private ControlEndpoint successfulFallback;

    private ClientFailoverIdentity(Path storePath, ControlReconnectLauncher launcher) {
        this.store = new ClientFailoverEndpointStore(storePath);
        this.orchestrator = new ControlReconnectOrchestrator(launcher, List.of(), List.of());
    }

    public static synchronized void initialize(Path storePath, ControlReconnectLauncher launcher) {
        instance = new ClientFailoverIdentity(
                Objects.requireNonNull(storePath, "storePath"),
                Objects.requireNonNull(launcher, "launcher"));
    }

    public static void prepareInitialConnection(String primaryAddress) {
        ClientFailoverIdentity current = instance;
        if (current != null) current.prepare(primaryAddress);
    }

    public static void mergeAdvertisedCandidates(List<ControlEndpoint> advertised) {
        ClientFailoverIdentity current = instance;
        if (current != null) current.merge(advertised);
    }

    public static boolean onInitialTcpConnectionFailed() {
        ClientFailoverIdentity current = instance;
        return current != null && current.initialFailure();
    }

    public static void onPrimaryDisconnected(ControlEndpoint active, String reason) {
        ClientFailoverIdentity current = instance;
        if (current != null) current.primaryDisconnected(active, reason);
    }

    public static boolean isRecovering() {
        ClientFailoverIdentity current = instance;
        return current != null && current.orchestrator.isRecovering();
    }

    public static String cacheIdentity(String connectedAddress) {
        ClientFailoverIdentity current = instance;
        return current == null ? connectedAddress : current.cacheIdentityInternal(connectedAddress);
    }

    public static Optional<ControlEndpoint> consumeSuccessfulFallback() {
        ClientFailoverIdentity current = instance;
        if (current == null) return Optional.empty();
        synchronized (current) {
            ControlEndpoint result = current.successfulFallback;
            current.successfulFallback = null;
            return Optional.ofNullable(result);
        }
    }

    public static String primaryAddress() {
        ClientFailoverIdentity current = instance;
        return current == null ? null : current.primaryAddress;
    }

    public static boolean onHandshakeAccepted() {
        ClientFailoverIdentity current = instance;
        return current != null && current.acceptHandshake(null);
    }

    /** 用户主动退出标记（MixinConnection 在主线程 disconnect(Component) 时调用）。 */
    public static void markUserInitiatedDisconnect() {
        ClientFailoverIdentity current = instance;
        if (current != null) {
            current.orchestrator.markUserInitiatedDisconnect();
        }
    }

    /** Test-visible handshake hook; loader handlers call the public orchestrator hook below. */
    public static boolean onPrimaryHandshakeAccepted(ControlEndpoint active) {
        ClientFailoverIdentity current = instance;
        return current != null && current.acceptHandshake(active);
    }

    public static ControlReconnectOrchestrator orchestrator() {
        ClientFailoverIdentity current = instance;
        return current == null ? null : current.orchestrator;
    }

    private synchronized void prepare(String primary) {
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()) {
            org.slf4j.LoggerFactory.getLogger("Hassium/Identity").info(
                    "[diag] ClientFailoverIdentity.prepare primary={} (markPrimary + resetForNewSession + orchestrator.prepareInitialConnection)", primary);
        }
        primaryAddress = Objects.requireNonNull(primary, "primaryAddress");
        activeFallback = null;
        successfulFallback = null;
        ClientFailoverAttemptMarker.markPrimary(primary);
        // 全新会话：复位恢复状态机（TERMINAL 单边终态只在一个会话内有效）。
        ClientRecoveryState.getInstance().resetForNewSession();
        orchestrator.prepareInitialConnection(primary, store.load(primary));
    }

    private synchronized void merge(List<ControlEndpoint> advertised) {
        if (primaryAddress == null) return;
        List<ControlEndpoint> merged = store.merge(primaryAddress, advertised);
        // configured 只看本次握手真实通告；store 合并出的历史候选不算（未配置 controlReachableEndpoints 时不允许自动切换）。
        orchestrator.mergeAdvertisedCandidates(merged, advertised != null && !advertised.isEmpty());
    }

    private synchronized boolean initialFailure() {
        if (primaryAddress == null) return false;
        boolean launched = orchestrator.onInitialTcpConnectionFailed();
        if (launched) {
            activeFallback = orchestrator.currentLaunchedEndpoint();
            ClientFailoverAttemptMarker.mark(primaryAddress, activeFallback);
        } else {
            ClientFailoverAttemptMarker.clear();
        }
        return launched;
    }

    private synchronized void primaryDisconnected(ControlEndpoint active, String reason) {
        if (primaryAddress == null) return;
        orchestrator.onPrimaryDisconnected(active, reason);
        // 与 initialFailure() 同款:orchestrator 已 launch 下一候选,这里记录 marker +
        // activeFallback,让 MixinMinecraft 能拦截该候选 DNS/TCP 失败并轮转,握手成功后
        // cacheIdentity 返回主地址(否则会以临时 hassium-failover:host:port 分裂缓存)。
        ControlEndpoint launched = orchestrator.currentLaunchedEndpoint();
        if (launched != null) {
            activeFallback = launched;
            ClientFailoverAttemptMarker.mark(primaryAddress, activeFallback);
        } else {
            // 候选耗尽:orchestrator 已走 terminal finalization;清 marker 避免 DisconnectedScreen
            // 误拦普通 logout,activeFallback 复位避免污染下一次连接身份。
            activeFallback = null;
            ClientFailoverAttemptMarker.clear();
        }
    }

    private synchronized boolean acceptHandshake(ControlEndpoint active) {
        boolean recovered = orchestrator.onHandshakeAccepted();
        if (active == null) {
            active = activeFallback != null ? activeFallback : orchestrator.currentLaunchedEndpoint();
        }
        if (active != null && activeFallback != null
                && active.coordinateKey().equals(activeFallback.coordinateKey())) {
            successfulFallback = active;
            ClientFailoverAttemptMarker.clear();
        } else if (!recovered) {
            activeFallback = null;
            ClientFailoverAttemptMarker.clear();
        }
        return recovered;
    }

    private synchronized String cacheIdentityInternal(String connectedAddress) {
        if (primaryAddress != null && activeFallback != null
                && connectedAddress.equals(activeFallback.host() + ":" + activeFallback.port())) {
            return primaryAddress;
        }
        return connectedAddress;
    }
}
