package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6 — {@link ControlFailoverHandler} 授权逻辑测试。
 *
 * <p>验证 plan §651-675 的核心不变量：
 * <ul>
 *   <li>主控制连接仍在活跃推进时（{@code lastControlActivity} 距 {@code now} ≤ {@code controlStallMs}）
 *       → {@code REJECTED_ACTIVE}。</li>
 *   <li>主连接停顿超时 + UDP 会话存在 + epoch 匹配 → {@code PERMITTED}，旧 master 关闭，
 *       failoverPermit 列表新增一项。</li>
 *   <li>无 UDP 会话 → {@code NO_UDP_SESSION}；epoch 不匹配 → {@code EPOCH_MISMATCH}；
 *       无主连接注册 → {@code NO_CONNECTION}。</li>
 * </ul>
 *
 * <p>用 FakeConnection 代替真实 Minecraft Connection；不触碰 Netty 资源。
 */
class ControlFailoverHandlerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private FakeConnection connection;
    private ControlFailoverHandler handler;

    @BeforeEach
    void setUp() {
        connection = new FakeConnection();
        handler = ControlFailoverHandler.forTest(PLAYER, 7L, connection);
    }

    @Test
    @DisplayName("控制连接仍在活跃 → REJECTED_ACTIVE")
    void rejectsFailoverWhileControlMasterIsStillProgressing() {
        handler.recordControlActivity(PLAYER, 7L, 10_000L);
        handler.declareUdpSessionForTest(PLAYER, 7L, 2);

        assertEquals(ControlFailoverHandler.FailoverResult.REJECTED_ACTIVE,
                handler.requestFailover(PLAYER, 7L, 2, 12_000L));
        assertFalse(connection.closed, "活跃期不应当关闭主连接");
        assertTrue(handler.permits().isEmpty(), "无 permit 颁布");
    }

    @Test
    @DisplayName("主控制停顿超时 + 有 UDP 会话 + epoch 匹配 → PERMITTED 并关闭旧 master")
    void stalledMasterIsClosedAndPermitIsIssued() {
        handler.recordControlActivity(PLAYER, 7L, 0L);
        handler.declareUdpSessionForTest(PLAYER, 7L, 2);

        assertEquals(ControlFailoverHandler.FailoverResult.PERMITTED,
                handler.requestFailover(PLAYER, 7L, 2, 6_001L));
        assertTrue(connection.closed, "permit 颁布后旧 master 必须被关闭");
        assertEquals(1, handler.permits().size(), "permit 表新增一项");
        ControlFailoverHandler.Permit p = handler.permits().get(0);
        assertEquals(PLAYER, p.playerId());
        assertEquals(7L, p.epoch());
        assertEquals(2, p.endpointId());
        assertTrue(p.permitMs() <= 6_001L);
    }

    @Test
    @DisplayName("无注册 master 连接 → NO_CONNECTION")
    void noRegisteredConnection() {
        ControlFailoverHandler h = ControlFailoverHandler.forTest();
        h.declareUdpSessionForTest(PLAYER, 1L, 2);
        assertEquals(ControlFailoverHandler.FailoverResult.NO_CONNECTION,
                h.requestFailover(PLAYER, 1L, 2, 10_000L));
    }

    @Test
    @DisplayName("无匹配的 UDP 会话 → NO_UDP_SESSION")
    void noUdpSessionForMatchingEpoch() {
        // 活动 stale；无 UDP 会话宣告
        handler.recordControlActivity(PLAYER, 2L, 0L);
        assertEquals(ControlFailoverHandler.FailoverResult.NO_UDP_SESSION,
                handler.requestFailover(PLAYER, 2L, 1, 10_000L));
        assertFalse(connection.closed);
    }

    @Test
    @DisplayName("epoch 不匹配 → EPOCH_MISMATCH")
    void epochMismatch() {
        handler.recordControlActivity(PLAYER, 1L, 0L);
        handler.declareUdpSessionForTest(PLAYER, 1L, 1);
        assertEquals(ControlFailoverHandler.FailoverResult.EPOCH_MISMATCH,
                handler.requestFailover(PLAYER, 9L, 1, 10_000L));
        assertFalse(connection.closed);
    }

    @Test
    @DisplayName("旧 epoch 的 UDP bind 不得覆盖新 master")
    void staleUdpSessionCannotReplaceCurrentControlEpoch() {
        handler.recordControlActivity(PLAYER, 8L, 0L);
        handler.onUdpSessionEstablished(PLAYER, 7L);

        assertEquals(ControlFailoverHandler.FailoverResult.NO_UDP_SESSION,
                handler.requestFailover(PLAYER, 8L, 1, 10_000L));
        assertFalse(connection.closed);
    }

    @Test
    @DisplayName("新 master 产生新 epoch 并撤销旧 UDP 授权")
    void newMasterAdvancesEpochAndRevokesPriorUdpAuthorization() {
        handler.declareUdpSessionForTest(PLAYER, 7L, 1);

        long nextEpoch = handler.beginControlConnection(PLAYER, new FakeConnection());

        assertTrue(nextEpoch > 7L);
        assertEquals(ControlFailoverHandler.FailoverResult.NO_UDP_SESSION,
                handler.requestFailover(PLAYER, nextEpoch, 1, 10_000L));
    }

    @Test
    @DisplayName("permit 关闭旧 master 后保留 epoch，供 disconnect 路径建立同 epoch lease")
    void permittedFailoverRetainsEpochUntilDisconnectCleanup() {
        handler.recordControlActivity(PLAYER, 7L, 0L);
        handler.declareUdpSessionForTest(PLAYER, 7L, 2);

        assertEquals(ControlFailoverHandler.FailoverResult.PERMITTED,
                handler.requestFailover(PLAYER, 7L, 2, 6_001L));
        assertEquals(7L, handler.currentEpoch(PLAYER));
    }

    @Test
    @DisplayName("remove(playerId) 清空该玩家所有状态 → 后续 NO_CONNECTION")
    void removeClearsState() {
        handler.recordControlActivity(PLAYER, 7L, 0L);
        handler.declareUdpSessionForTest(PLAYER, 7L, 1);
        handler.remove(PLAYER);
        assertEquals(ControlFailoverHandler.FailoverResult.NO_CONNECTION,
                handler.requestFailover(PLAYER, 7L, 1, 10_000L));
        assertTrue(handler.permits().isEmpty());
    }

    /** Fake connection：仅记录是否被关闭。 */
    static final class FakeConnection implements Runnable {
        volatile boolean closed = false;
        @Override public void run() { closed = true; }
    }
}
