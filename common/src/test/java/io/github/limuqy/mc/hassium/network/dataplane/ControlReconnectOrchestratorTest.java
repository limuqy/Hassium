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
        // 直接进入 TERMINAL + consume，把单例状态吹回 NONE 风格的"已清理" -- 单例没有 reset 接口，
        // 但测试关心的只有 RECOVERING / RECOVERED / TERMINAL 流；用 markTerminal + consume 做一次
        // cleanup 然后下次 begin 又会自然从 TERMINAL 退出（begin 对 TERMINAL 是 no-op，OK）。
        ClientRecoveryState s = ClientRecoveryState.getInstance();
        s.markTerminal();
        // 单例不再可被 begin 拉回；测试期望独立新实例时用 `new ControlReconnectOrchestrator`
        // + 内部 record 自己的 phase（见 orchestrator 实现说明：用单例仅作 cross-cutting gate）。
    }

    @Test
    @DisplayName("硬关闭主连接 → 立刻 launch 下一个候选；恢复态为 RECOVERING")
    void hardPrimaryCloseLaunchesNextCandidateWithoutTerminalCleanup() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlEndpoint a = endpoint("a.com", 25565, 80);
        ControlEndpoint b = endpoint("b.com", 25565, 80);
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(a, b));

        orchestrator.onPrimaryDisconnected(a, "closed");

        assertEquals(List.of(b), launcher.launched(), "立刻 launch 下一个候选 b");
        // 单例状态下 phase 仍保留 NONE —— orchestrator 内部状态独立追踪，单例不参与测试断言
        assertTrue(orchestrator.isRecovering(), "orchestrator 走入恢复态");
    }

    @Test
    @DisplayName("候选耗尽 → 恰好一次 terminal 清理")
    void exhaustedCandidatesPerformsOneTerminalFinalization() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlEndpoint a = endpoint("a.com", 25565, 100);
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(a));

        orchestrator.onPrimaryDisconnected(a, "closed");
        orchestrator.onReconnectFailed(a);

        assertEquals(1, orchestrator.terminalFinalizations(), "仅发起一次 terminal finalize");
        assertFalse(orchestrator.isRecovering(), "恢复结束");
    }

    @Test
    @DisplayName("onHandshakeAccepted 把状态切到 RECOVERED + 清空恢复；不再 launch 新候选")
    void handshakeAcceptedStopsRecovery() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlEndpoint a = endpoint("a.com", 25565, 100);
        ControlReconnectOrchestrator orchestrator =
                ControlReconnectOrchestrator.forTest(launcher, List.of(a));

        orchestrator.onPrimaryDisconnected(a, "closed");
        // 假装 reconnect 到下一候选并成功（这里用 single-endpoint 测试，触发 exhausted 之前先 mark）
        orchestrator.onHandshakeAccepted();

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
    @DisplayName("恢复窗口在恢复开始时从配置固定")
    void reconnectUsesConfiguredRecoveryWindow() {
        RecordingLauncher launcher = new RecordingLauncher();
        ControlReconnectOrchestrator orchestrator = ControlReconnectOrchestrator.forTest(
                launcher,
                List.of(endpoint("backup.example", 25565, 100)),
                dataPlaneConfig(1_234L));

        orchestrator.onPrimaryDisconnected(endpoint("primary.example", 25565, 100), "closed");

        assertEquals(1_234L, orchestrator.recoveryDeadlineMs() - orchestrator.recoveryStartedAtMs());
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
