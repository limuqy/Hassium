package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.ResumeTicket;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationEndpoint;
import io.github.limuqy.mc.hassium.network.core.migration.MigrationPolicy;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameType;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.OutboundConnection;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NetworkCore 迁移流程（T8）：migrateTo 状态流转（ACTIVE→MIGRATING→ACTIVE）、
 * 续流票据随握手上线、预热接管、故障触发立即迁移。
 */
class NetworkCoreMigrationTest {

    private static final byte[] TOKEN = new byte[16];
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final MigrationEndpoint MASTER_B = new MigrationEndpoint("b.example", 25566);

    private final AtomicLong clock = new AtomicLong(200_000L);

    private static HandshakeCodec.ClientRequestOptions testOptions() {
        return new HandshakeCodec.ClientRequestOptions(
                Constants.CURRENT_PROTOCOL_VERSION, Constants.MOD_VERSION,
                Constants.NETWORK_COMPRESSION_ALGORITHM,
                true, true, 12.5, -34.0, false, true);
    }

    /** 灌入握手响应（S2C 尾带 resumeAccepted）。 */
    private static void acceptHandshake(OutboundConnection conn, boolean resumeAccepted) {
        EmbeddedChannel embedded = (EmbeddedChannel) conn.channel();
        ByteBuf response = HandshakeCodec.encodeServerResponse(1, true, true, true,
                UdpDataPlaneHandshakeTail.S2CTail.disabled(), 0L, null, false);
        HandshakeStateTail.writeS2C(response, new HandshakeStateTail.S2C(resumeAccepted));
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_S2C, response));
        response.release();
    }

    /** 重置单例（跨测试共享）+ 引擎恢复默认。 */
    private static void resetCore(NetworkCore core) {
        core.onDisconnect();
        core.migration().setTargetEndpoints(List.of());
        core.migration().setClock(System::currentTimeMillis);
        core.migration().setConnectionFactory(null);
        core.migration().setPlayerIdSource(null);
        core.migration().setPlayerStateSource(null);
        core.migration().setPolicy(MigrationPolicy.DEFAULT);
    }

    /** 嵌入式连接工厂（记录创建序）。 */
    private static void installEmbeddedFactory(NetworkCore core, List<OutboundConnection> created) {
        core.migration().setConnectionFactory((host, port, options, tail, listener) -> {
            OutboundConnection c = OutboundConnection.openEmbedded(options, listener, tail);
            created.add(c);
            return c;
        });
    }

    /** 玩家源（票据身份 + 位置上报）。 */
    private static void installPlayerSources(NetworkCore core) {
        core.migration().setPlayerIdSource(() -> PLAYER);
        core.migration().setPlayerStateSource(() ->
                new PlayerStateReport(10, 20, 30, 90, 0, "minecraft:overworld"));
    }

    /** 经 embedded 缝建立 ACTIVE 会话。 */
    private static OutboundConnection establishActive(NetworkCore core) {
        core.transition(NetworkCoreState.IDLE, NetworkCoreState.CONNECTING);
        OutboundConnection connA = OutboundConnection.openEmbedded(testOptions(), core);
        core.attach(connA);
        acceptHandshake(connA, false);
        assertEquals(NetworkCoreState.ACTIVE, core.state());
        return connA;
    }

    @Test
    void migrateToStateFlowWithResumeTicket() {
        NetworkCore core = NetworkCore.getInstance();
        resetCore(core);
        List<OutboundConnection> created = new ArrayList<>();
        installEmbeddedFactory(core, created);
        installPlayerSources(core);
        core.migration().setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 60000L, false));

        OutboundConnection connA = establishActive(core);
        long epochBefore = core.migration().currentEpoch();

        // migrateTo：ACTIVE → MIGRATING → 关旧 outbound → 连接新主控（续流票据）
        core.migrateTo(MASTER_B);
        assertEquals(NetworkCoreState.MIGRATING, core.state());
        assertFalse(connA.isOpen(), "直接切换：旧 outbound 应已关闭");
        assertEquals(1, created.size());
        OutboundConnection connB = created.get(0);
        assertSame(connB, core.outbound());

        // 新 outbound 握手帧携带续流尾：位置上报 + 票据（epoch 递增，验签通过）
        EmbeddedChannel embeddedB = (EmbeddedChannel) connB.channel();
        ByteBuf handshake = embeddedB.readOutbound();
        assertNotNull(handshake, "新连接应已发握手帧");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(handshake);
            assertEquals(ControlFrameType.HANDSHAKE_C2S, frame.type());
            HandshakeCodec.decodeClientRequest(frame.payload());
            HandshakeStateTail.C2S tail = HandshakeStateTail.readC2S(frame.payload());
            assertNotNull(tail, "迁移握手应携带续流状态尾");
            assertTrue(tail.resumeRequested());
            assertTrue(tail.state().present());
            assertEquals("minecraft:overworld", tail.state().dimension());
            ResumeTicket ticket = ResumeTicket.decode(tail.resumeTicket());
            assertEquals(PLAYER, ticket.playerId());
            assertEquals(epochBefore + 1, ticket.epoch(), "epoch 进程内单调递增");
            assertTrue(ticket.verify(), "票据验签通过");
            frame.payload().release();
        } finally {
            handshake.release();
        }

        // 主控接受续流 → MIGRATING → ACTIVE
        acceptHandshake(connB, true);
        assertEquals(NetworkCoreState.ACTIVE, core.state());
        assertTrue(core.lastResumeAccepted(), "续流就绪");
        assertSame(connB, core.outbound());

        core.onDisconnect();
    }

    @Test
    void migrateToWithPrewarmPromotesWarmSession() {
        NetworkCore core = NetworkCore.getInstance();
        resetCore(core);
        List<OutboundConnection> created = new ArrayList<>();
        installEmbeddedFactory(core, created);
        installPlayerSources(core);
        core.migration().setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 60000L, true));

        OutboundConnection connA = establishActive(core);

        // 预热路径：旧 outbound 保持（重叠切换），预热连接建立
        core.migrateTo(MASTER_B);
        assertEquals(NetworkCoreState.MIGRATING, core.state());
        assertTrue(connA.isOpen(), "预热期间旧 outbound 继续服务");
        assertEquals(1, created.size());
        OutboundConnection prewarmConn = created.get(0);

        // 预热握手帧携带续流尾（位置上报 → B 侧物化 + resyncTrackedChunks）
        EmbeddedChannel embeddedP = (EmbeddedChannel) prewarmConn.channel();
        ByteBuf handshake = embeddedP.readOutbound();
        assertNotNull(handshake);
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(handshake);
            assertEquals(ControlFrameType.HANDSHAKE_C2S, frame.type());
            HandshakeCodec.decodeClientRequest(frame.payload());
            HandshakeStateTail.C2S tail = HandshakeStateTail.readC2S(frame.payload());
            assertNotNull(tail);
            assertTrue(tail.resumeRequested());
            assertNotNull(tail.resumeTicket());
            frame.payload().release();
        } finally {
            handshake.release();
        }

        // B 接受续流 → 预热就绪 → 接管：旧连接关闭、outbound=预热连接、ACTIVE
        acceptHandshake(prewarmConn, true);
        assertEquals(NetworkCoreState.ACTIVE, core.state());
        assertFalse(connA.isOpen(), "接管后旧 outbound 关闭（写权移交）");
        assertSame(prewarmConn, core.outbound());
        assertTrue(core.lastResumeAccepted());

        core.onDisconnect();
    }

    @Test
    void faultTriggerMigratesImmediately() {
        NetworkCore core = NetworkCore.getInstance();
        resetCore(core);
        List<OutboundConnection> created = new ArrayList<>();
        installEmbeddedFactory(core, created);
        installPlayerSources(core);
        core.migration().setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 60000L, true));
        core.migration().setTargetEndpoints(List.of(MASTER_B));
        core.migration().setClock(clock::get);

        OutboundConnection connA = establishActive(core);
        assertEquals(0, created.size());
        long faultsBefore = core.migration().faultsDetected();
        long migrationsBefore = core.migration().migrationsStarted();

        // 入站静默超过 faultTimeout（60000）→ 故障 → 立即迁移到 B（不预热）
        clock.set(clock.get() + 60000 + 1);
        core.migration().tick(clock.get());

        assertEquals(NetworkCoreState.MIGRATING, core.state());
        assertEquals(faultsBefore + 1, core.migration().faultsDetected());
        assertEquals(migrationsBefore + 1, core.migration().migrationsStarted());
        assertFalse(connA.isOpen(), "故障路径：旧 outbound 关闭");
        assertEquals(1, created.size(), "故障路径不预热，直接建新连接");
        OutboundConnection connB = created.get(0);
        assertSame(connB, core.outbound());

        // 新连接续流接受 → ACTIVE
        acceptHandshake(connB, true);
        assertEquals(NetworkCoreState.ACTIVE, core.state());

        core.onDisconnect();
    }
}
