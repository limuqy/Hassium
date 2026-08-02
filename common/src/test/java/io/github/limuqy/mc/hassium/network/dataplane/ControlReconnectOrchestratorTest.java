package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.AfterEach;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 9 — {@link ControlReconnectOrchestrator} 单元测试。
 *
 * <p>Launcher 是注入 seam，{@link RecordingLauncher} 累积每条 launched 端点 + 失败回调；
 * orchestrator 自身只在 common 模块运转，触碰 {@link ClientRecoveryState}（单例）+ 新候选管理。
 *
 * <p>每个 case 末尾强制 reset 单例以避免跨测试污染（{@link ClientRecoveryState} 是 process singleton）。
 */
class ControlReconnectOrchestratorTest {

    @BeforeEach
    @AfterEach
    void resetRecoverySingleton() {
        // 直接复位到 NONE（L2 新增 resetForNewSession）；此前用 markTerminal + consume 逼近
        // 是因为单例没有公开 reset —— 每次测试从干净状态开始。
        ClientRecoveryState.getInstance().resetForNewSession();
    }

    @Test
    @DisplayName("硬关闭主连接 → 立刻 launch 下一个候选；恢复态为 RECOVERING")
    void hardPrimaryCloseLaunchesNextCandidateWithoutTerminalCleanup() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlEndpoint a = endpoint("a.com", 25565, 80);
        ControlEndpoint b = endpoint("b.com", 25565, 80);
        ControlEndpoint c = endpoint("c.com", 25565, 80);
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(a, b, c));

        orchestrator.onPrimaryDisconnected(a, "closed");

        assertEquals(List.of(b), launcher.launched(), "立刻 launch 下一个候选 b");
        // 单例状态下 phase 仍保留 NONE —— orchestrator 内部状态独立追踪，单例不参与测试断言
        assertTrue(orchestrator.isRecovering(), "orchestrator 走入恢复态");
    }

    @Test
    @DisplayName("去重后端点不足（仅 active）→ gate 拒绝，不 launch 不 terminal")
    void soleActiveEndpointRejectedByGate() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlEndpoint a = endpoint("a.com", 25565, 100);
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(a));

        orchestrator.onPrimaryDisconnected(a, "closed");

        assertTrue(launcher.launched().isEmpty(), "端点去重后仅 1 个 → 不 launch");
        assertEquals(0, orchestrator.terminalFinalizations(), "gate 拒绝不触发 terminal");
        assertFalse(orchestrator.isRecovering(), "不得悬挂 recovering");
        assertFalse(orchestrator.onHandshakeAccepted(), "后续普通握手不得误报 failover 恢复");
    }

    @Test
    @DisplayName("onHandshakeAccepted 把状态切到 RECOVERED + 清空恢复；不再 launch 新候选")
    void handshakeAcceptedStopsRecovery() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlEndpoint a = endpoint("a.com", 25565, 100);
        ControlEndpoint b = endpoint("b.com", 25565, 80);
        ControlEndpoint c = endpoint("c.com", 25565, 80);
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(a, b, c));

        orchestrator.onPrimaryDisconnected(a, "closed");
        assertEquals(List.of(b), launcher.launched(), "应立刻 launch 下一候选 b");
        // 假装 reconnect 到 b 并成功
        assertTrue(orchestrator.onHandshakeAccepted(), "恢复中的握手应记为 failover 恢复");

        assertFalse(orchestrator.isRecovering(), "handshake 接收立即结束恢复态");
        assertEquals(0, orchestrator.terminalFinalizations(), "成功不触发 terminal");
    }

    @Test
    @DisplayName("初始握手不是 failover 恢复")
    void initialHandshakeIsNotReportedAsRecovery() {
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(new RecordingLauncher(), List.of());

        assertFalse(orchestrator.onHandshakeAccepted(), "没有断开上下文的首次握手不得报告恢复成功");
    }

    @Test
    @DisplayName("FailoverPermit 同 epoch 触发 attempts next candidate；过期/不匹配忽略")
    void failoverPermitOnlyLaunchesOnMatchingNonExpiredEpoch() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlEndpoint a = endpoint("a.com", 25565, 100);
        ControlEndpoint b = endpoint("b.com", 25565, 90);
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(a, b));

        // 不 begin 先看：permit 不匹配当前 epoch 被忽略
        orchestrator.onFailoverPermit(/*connectionEpoch*/ 999L, /*expiry now*/ System.currentTimeMillis() + 10_000L);
        assertEquals(0, launcher.launched().size(), "permit 不在任何恢复 epoch 上 → 无 launch");

        // begin recovery with epoch = 1：起点 a 失活 → 候选 b 立即 launch
        orchestrator.beginRecoveryForTest(1L, a);
        assertEquals(List.of(b), launcher.launched(), "begin 立刻 launch b (a 失活)");
        int beforePermit = launcher.launched().size();

        // matching epoch permit — accepted，不另行 launch（候选已 launch at begin）
        orchestrator.onFailoverPermit(1L, System.currentTimeMillis() + 10_000L);
        assertEquals(beforePermit, launcher.launched().size(), "permit 本身不直接 launch");
        assertTrue(orchestrator.isRecovering(), "匹配 permit 不终止恢复");

        // expired permit ignored
        long past = System.currentTimeMillis() - 5_000L;
        orchestrator.onFailoverPermit(1L, past);
        assertTrue(orchestrator.isRecovering(), "过期 permit 不终止恢复");
    }

    @Test
    @DisplayName("未通告候选（controlReachableEndpoints 未配置）→ 拒绝自动 failover")
    void unadvertisedCandidatesRejectRecovery() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator =
                new ControlReconnectOrchestrator(launcher, List.of(), List.of());
        orchestrator.prepareInitialConnection("primary.com:25565", List.of(
                endpoint("a.com", 25565, 10), endpoint("b.com", 25565, 9)));
        // 未调 mergeAdvertisedCandidates：本次握手未通告

        orchestrator.onPrimaryDisconnected(endpoint("primary.com", 25565, 100), "closed");

        assertTrue(launcher.launched().isEmpty(), "未通告 → 不 launch");
        assertEquals(0, orchestrator.terminalFinalizations(), "gate 拒绝不触发 terminal");
        assertFalse(orchestrator.isRecovering(), "未通告 → 不进入恢复态");
    }

    @Test
    @DisplayName("用户主动退出 → 拒绝自动 failover（正常退出不重连）")
    void userInitiatedDisconnectRejectsRecovery() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(
                        endpoint("a.com", 25565, 100),
                        endpoint("b.com", 25565, 90),
                        endpoint("c.com", 25565, 80)));

        orchestrator.markUserInitiatedDisconnect();
        orchestrator.onPrimaryDisconnected(endpoint("a.com", 25565, 100), "closed");

        assertTrue(launcher.launched().isEmpty(), "主动退出 → 不 launch");
        assertEquals(0, orchestrator.terminalFinalizations());
        assertFalse(orchestrator.isRecovering(), "主动退出 → 不进入恢复态");
    }

    @Test
    @DisplayName("端点去重后总数 ≤ 2 → 拒绝自动 failover")
    void insufficientDistinctEndpointsRejectRecovery() {
        RecordingLauncher launcher = new RecordingLauncher();
        // 仅主地址 + 1 候选
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(
                        endpoint("a.com", 25565, 100),
                        endpoint("b.com", 25565, 90)));

        orchestrator.onPrimaryDisconnected(endpoint("a.com", 25565, 100), "closed");

        assertTrue(launcher.launched().isEmpty(), "去重后仅 2 端点 → 不 launch");
        assertEquals(0, orchestrator.terminalFinalizations());
        assertFalse(orchestrator.isRecovering());
    }

    @Test
    @DisplayName("候选与主地址同坐标时按去重后计数（重复不算端点）")
    void duplicateCandidatesAgainstPrimaryRejectRecovery() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator =
                new ControlReconnectOrchestrator(launcher, List.of(), List.of());
        orchestrator.prepareInitialConnection("a.com:25565", List.of());
        orchestrator.mergeAdvertisedCandidates(List.of(
                endpoint("a.com", 25565, 10),   // 与主地址同坐标
                endpoint("b.com", 25565, 9)));  // 去重后只有 2 个端点

        orchestrator.onPrimaryDisconnected(endpoint("a.com", 25565, 100), "closed");

        assertTrue(launcher.launched().isEmpty(), "去重后端点数 2 → 拒绝");
        assertEquals(0, orchestrator.terminalFinalizations());
        assertFalse(orchestrator.isRecovering());
    }

    @Test
    @DisplayName("通告 + 非主动退出 + 去重端点 > 2 → 放行自动 failover")
    void sufficientDistinctEndpointsAllowRecovery() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator =
                new ControlReconnectOrchestrator(launcher, List.of(), List.of());
        orchestrator.prepareInitialConnection("primary.com:25565", List.of());
        orchestrator.mergeAdvertisedCandidates(List.of(
                endpoint("a.com", 25565, 10),
                endpoint("b.com", 25565, 9)));

        orchestrator.onPrimaryDisconnected(endpoint("primary.com", 25565, 100), "closed");

        assertEquals(List.of(endpoint("a.com", 25565, 10)), launcher.launched(),
                "放行 → launch 高优先级候选");
        assertTrue(orchestrator.isRecovering(), "放行 → 进入恢复态");
    }

    @Test
    @DisplayName("初始 TCP 失败但无任何候选 → 拒绝自动切换主控")
    void initialTcpFailureWithoutCandidateRejects() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator =
                new ControlReconnectOrchestrator(launcher, List.of(), List.of());
        // 无 persisted 候选（从未成功握手通告过）：初始失败没有备用可切。
        orchestrator.prepareInitialConnection("primary.com:25565", List.of());

        assertFalse(orchestrator.onInitialTcpConnectionFailed(), "无候选 → 不自动切换");
        assertTrue(launcher.launched().isEmpty());
        assertFalse(orchestrator.isRecovering());
    }

    @Test
    @DisplayName("初始 TCP 失败但 persisted 有历史候选（本次未通告）→ 切备用进服")
    void initialTcpFailureWithPersistedCandidatesLaunchesFallback() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator =
                new ControlReconnectOrchestrator(launcher, List.of(), List.of());
        // 热切/上次会话握手已把候选写入 persisted store；本次新连接未及握手即失败。
        orchestrator.prepareInitialConnection("primary.com:25565", List.of(
                endpoint("a.com", 25565, 10), endpoint("b.com", 25565, 9)));
        // 未调 mergeAdvertisedCandidates（TCP 都没连上，无握手）

        assertTrue(orchestrator.onInitialTcpConnectionFailed(), "persisted 候选存在 → 切备用");
        assertEquals(List.of(endpoint("a.com", 25565, 10)), launcher.launched(),
                "按 priority 优先 launch a");
        assertTrue(orchestrator.isRecovering());
    }

    @Test
    @DisplayName("恢复窗口在恢复开始时从配置固定")
    void reconnectUsesConfiguredRecoveryWindow() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator = ControlReconnectOrchestrator.forTest(
                launcher,
                List.of(endpoint("backup.example", 25565, 100),
                        endpoint("backup2.example", 25565, 90)),
                dataPlaneConfig(1_234L));

        orchestrator.onPrimaryDisconnected(endpoint("primary.example", 25565, 100), "closed");

        assertEquals(1_234L, orchestrator.recoveryDeadlineMs() - orchestrator.recoveryStartedAtMs());
    }

    @Test
    @DisplayName("recovering 分支候选耗尽 → TERMINAL + terminalFinalizations 递增（L2 不再悬挂 recovering）")
    void recoveringBranchExhaustionReachesTerminal() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator =
                new ControlReconnectOrchestrator(launcher, List.of(), List.of());
        orchestrator.prepareInitialConnection("primary.com:25565", List.of(
                endpoint("b.com", 25565, 90), endpoint("c.com", 25565, 80)));
        orchestrator.mergeAdvertisedCandidates(List.of(
                endpoint("b.com", 25565, 90), endpoint("c.com", 25565, 80)));

        // 初始分支：首连失败 → launch b
        assertTrue(orchestrator.onInitialTcpConnectionFailed(), "初始失败应启动恢复并 launch b");
        assertEquals(List.of(endpoint("b.com", 25565, 90)), launcher.launched());
        // recovering 分支：候选 b 失败 → 轮转 c
        assertTrue(orchestrator.onInitialTcpConnectionFailed(), "候选 b 失败应轮转到 c");
        assertEquals(List.of(endpoint("b.com", 25565, 90), endpoint("c.com", 25565, 80)),
                launcher.launched());
        // recovering 分支：候选 c 失败 → 耗尽 → terminal（而非仅 recovering=false 悬挂）
        assertFalse(orchestrator.onInitialTcpConnectionFailed(), "候选耗尽应返回 false");

        assertEquals(ClientRecoveryState.Phase.TERMINAL, ClientRecoveryState.getInstance().phase(),
                "耗尽后恢复状态机必须进入 TERMINAL（L2 F8 终态观察依赖）");
        assertEquals(1, orchestrator.terminalFinalizations(), "terminal finalize 恰好一次");
        assertFalse(orchestrator.isRecovering(), "耗尽后不得悬挂 recovering");
    }

    @Test
    @DisplayName("initial 分支耗尽（恢复窗口即刻过期）→ TERMINAL")
    void initialBranchExhaustionReachesTerminal() {
        RecordingLauncher launcher = new RecordingLauncher();
        // 窗口 0ms：startRecovery 立即清空 remaining → launchNextCandidate 失败 → 初始分支耗尽。
        // （DataPlaneConfig 校验要求正窗口，直接经包内构造注入 LongSupplier。）
        ControlReconnectOrchestrator orchestrator =
                new ControlReconnectOrchestrator(launcher, List.of(), List.of(), () -> 0L);
        orchestrator.prepareInitialConnection("primary.com:25565", List.of(
                endpoint("b.com", 25565, 90), endpoint("c.com", 25565, 80)));
        orchestrator.mergeAdvertisedCandidates(List.of(
                endpoint("b.com", 25565, 90), endpoint("c.com", 25565, 80)));

        // 窗口 0ms：startRecovery 立即清空 remaining → launchNextCandidate 失败 → 初始分支耗尽
        assertFalse(orchestrator.onInitialTcpConnectionFailed());
        assertEquals(0, launcher.launched().size(), "窗口过期不得 launch");

        assertEquals(ClientRecoveryState.Phase.TERMINAL, ClientRecoveryState.getInstance().phase());
        assertEquals(1, orchestrator.terminalFinalizations());
        assertFalse(orchestrator.isRecovering());
    }

    @Test
    @DisplayName("恢复中 gate 拒绝（用户主动退出标记）→ terminal 终结恢复窗口")
    void gateRejectWhileRecoveringTerminates() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator = ControlReconnectOrchestrator.forTest(
                launcher,
                List.of(endpoint("a.com", 25565, 100), endpoint("b.com", 25565, 90),
                        endpoint("c.com", 25565, 80)));
        orchestrator.prepareInitialConnection("primary.com:25565", List.of(
                endpoint("a.com", 25565, 100), endpoint("b.com", 25565, 90),
                endpoint("c.com", 25565, 80)));
        orchestrator.mergeAdvertisedCandidates(List.of(
                endpoint("a.com", 25565, 100), endpoint("b.com", 25565, 90),
                endpoint("c.com", 25565, 80)));

        // 主控断开 → 恢复启动并 launch 高优先级候选
        orchestrator.onPrimaryDisconnected(endpoint("primary.com", 25565, 100), "closed");
        assertEquals(1, launcher.launched().size(), "应启动恢复");
        assertTrue(orchestrator.isRecovering());

        // 恢复中用户主动退出标记 → 后续被动断连被 gate 拒绝 → 恢复窗口正常终结（不卡 freezeActive）
        orchestrator.markUserInitiatedDisconnect();
        orchestrator.onPrimaryDisconnected(endpoint("b.com", 25565, 90), "closed");

        assertEquals(ClientRecoveryState.Phase.TERMINAL, ClientRecoveryState.getInstance().phase());
        assertEquals(1, orchestrator.terminalFinalizations());
        assertFalse(orchestrator.isRecovering());
    }


    private static HassiumConfig.DataPlaneConfig dataPlaneConfig(long recoveryWindowMs) {
        return new HassiumConfig.DataPlaneConfig(
                true,
                HassiumConfig.ServerNetworkConfig.DEFAULT.dataPlane().udpListeners(),
                6_000L,
                30_000L,
                recoveryWindowMs);
    }

    // ===== fixture helpers =====

    static ControlEndpoint endpoint(String host, int port, int priority) {
        return new ControlEndpoint(host, port, priority);
    }

    /** 测试用 launcher —— 记录每个 launch 的端点 + 外部回调。 */
    static final class RecordingLauncher implements ControlReconnectLauncher {
        final List<ControlEndpoint> launched = new ArrayList<>();
        final List<Runnable> failureCallbacks = new ArrayList<>();

        @Override
        public void connect(ControlEndpoint endpoint, Runnable onFailure) {
            launched.add(endpoint);
            failureCallbacks.add(onFailure);
        }

        List<ControlEndpoint> launched() {
            return List.copyOf(launched);
        }
    }
}
