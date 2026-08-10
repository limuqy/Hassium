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
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NetworkCore 迁移流程（T8）：migrateTo 状态流转（ACTIVE→MIGRATING→ACTIVE）、
 * 续流票据随握手上线、预热接管、故障触发立即迁移。
 * T6（N1）：掉线期（MIGRATING/IDLE/静默期）routeC2S 已消费丢弃、resumeAccepted 位置回退。
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
        // currentEndpoint 也要清：nextEndpoint 排除当前端点，残留会让单目标端点故障测试退化为 IDLE
        core.migration().noteCurrentEndpoint(null);
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

    /** T3 配置化后的测试策略（silentTimeout=默认 10000 / faultTimeout=60000 → 生效静默超时 10000ms；无预热变体显式传 false）。 */
    private static MigrationPolicy testPolicy() {
        return new MigrationPolicy(15.0, 4.0, "", 5000L, 10000L, 60000L, 10000L, 0.5, true);
    }

    /** 通用假包（routeC2S 丢弃分类只关心包对象，不编码）。 */
    private static Packet<ServerGamePacketListener> fakePacket() {
        return new Packet<>() {
#if MC_VER < MC_1_20_5
            @Override
            public void write(FriendlyByteBuf buffer) {
            }
#else
            @Override
            public net.minecraft.network.protocol.PacketType<? extends Packet<ServerGamePacketListener>> type() {
                return null;
            }
#endif
            @Override
            public void handle(ServerGamePacketListener handler) {
            }
        };
    }

    /** 位置回退包坐标断言（1.21.2+ record 形态差异收敛）。 */
    private static void assertRollbackPosition(ClientboundPlayerPositionPacket packet,
                                               double x, double y, double z, float yaw, float pitch) {
#if MC_VER < MC_1_21_2
        assertEquals(x, packet.getX(), 1.0e-6);
        assertEquals(y, packet.getY(), 1.0e-6);
        assertEquals(z, packet.getZ(), 1.0e-6);
        assertEquals(yaw, packet.getYRot(), 1.0e-6);
        assertEquals(pitch, packet.getXRot(), 1.0e-6);
        assertTrue(packet.getRelativeArguments().isEmpty(), "绝对坐标回退（无相对标志）");
#else
        assertEquals(x, packet.change().position().x, 1.0e-6);
        assertEquals(y, packet.change().position().y, 1.0e-6);
        assertEquals(z, packet.change().position().z, 1.0e-6);
        assertEquals(yaw, packet.change().yRot(), 1.0e-6);
        assertEquals(pitch, packet.change().xRot(), 1.0e-6);
        assertTrue(packet.relatives().isEmpty(), "绝对坐标回退（无相对标志）");
#endif
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
        core.migration().setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 10000L, 60000L, 10000L, 0.5, false));

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
        core.migration().setPolicy(testPolicy());

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
        core.migration().setPolicy(testPolicy());
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

    @Test
    void routeC2SDropsDuringDisconnectPeriods() {
        NetworkCore core = NetworkCore.getInstance();
        resetCore(core);
        installPlayerSources(core);
        core.migration().setPolicy(testPolicy());
        core.migration().setClock(clock::get);

        OutboundConnection connA = establishActive(core);
        NetworkCore.C2SEncoder prevEncoder = core.c2sEncoder();
        core.setC2SEncoder(p -> Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));
        Packet<?> packet = fakePacket();
        long before = core.c2sRoutedCount();

        // ACTIVE + outbound 开：正常路由
        assertTrue(core.routeC2S(packet), "ACTIVE：应编码进 outbound 路由");

        // MIGRATING（切换窗口）：已消费丢弃——MixinConnection cancel 原版发送，不排队不重放
        assertTrue(core.transition(NetworkCoreState.ACTIVE, NetworkCoreState.MIGRATING));
        assertTrue(core.routeC2S(packet), "MIGRATING：掉线期已消费丢弃");

        // CONNECTING/HANDSHAKING（正常连接建立期）：原版直连兜底（登录期不可丢）
        assertTrue(core.transition(NetworkCoreState.MIGRATING, NetworkCoreState.CONNECTING));
        assertFalse(core.routeC2S(packet), "CONNECTING：原版直连兜底");
        assertTrue(core.transition(NetworkCoreState.CONNECTING, NetworkCoreState.HANDSHAKING));
        assertFalse(core.routeC2S(packet), "HANDSHAKING：原版直连兜底");

        // IDLE（onError 掉线）：已消费丢弃
        assertTrue(core.transition(NetworkCoreState.HANDSHAKING, NetworkCoreState.IDLE));
        assertTrue(core.routeC2S(packet), "IDLE：掉线期已消费丢弃");

        assertEquals(before + 5, core.c2sRoutedCount(), "每次调用计数 +1（可验证）");
        core.setC2SEncoder(prevEncoder);
        core.onDisconnect();
    }

    @Test
    void routeC2SDropsWhileInboundSilentDuringActive() {
        NetworkCore core = NetworkCore.getInstance();
        resetCore(core);
        installPlayerSources(core);
        // 显式 silentTimeoutMs（≠默认）→ resolvedSilentTimeoutMs 优先 silentTimeout = 30000ms
        core.migration().setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 30000L, 60000L, 10000L, 0.5, true));
        core.migration().setClock(clock::get);

        OutboundConnection connA = establishActive(core); // attach → bindHeartbeatTarget：lastInbound=200_000
        NetworkCore.C2SEncoder prevEncoder = core.c2sEncoder();
        core.setC2SEncoder(p -> Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));
        Packet<?> packet = fakePacket();
        EmbeddedChannel embedded = (EmbeddedChannel) connA.channel();
        embedded.readOutbound(); // 排空握手帧

        // 静默不足超时：正常路由
        clock.set(clock.get() + 29_999);
        assertTrue(core.routeC2S(packet), "静默 < 生效静默超时：正常路由");
        assertNotNull(embedded.readOutbound(), "路由应编码出 outbound 帧");

        // 静默 ≥ 生效静默超时（未 tick，状态仍 ACTIVE）：fault 前丢弃窗口 → 已消费丢弃（不排队）
        clock.set(clock.get() + 2);
        assertTrue(core.routeC2S(packet), "静默 ≥ 生效静默超时：fault 前丢弃窗口已消费丢弃");
        assertEquals(NetworkCoreState.ACTIVE, core.state(), "静默丢弃不触发状态变更（fault 由 tick 驱动）");
        assertNull(embedded.readOutbound(), "丢弃不应产生 outbound 帧");

        // 入站活动恢复 → 静默解除 → 恢复路由
        core.migration().noteInboundActivity();
        assertTrue(core.routeC2S(packet), "入站恢复：恢复路由");

        core.setC2SEncoder(prevEncoder);
        core.onDisconnect();
    }

    @Test
    void resumeAcceptedRollsBackPositionToSnapshot() {
        NetworkCore core = NetworkCore.getInstance();
        resetCore(core);
        List<OutboundConnection> created = new ArrayList<>();
        installEmbeddedFactory(core, created);
        installPlayerSources(core);
        // 无预热（直接切换）：迁移握手经 NetworkCore.onHandshakeAccepted 回调（预热路径走 promotePrewarm，不在 T6 方法区）
        core.migration().setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 10000L, 60000L, 10000L, 0.5, false));

        List<Packet<?>> dispatched = new ArrayList<>();
        core.registerS2CInjector(dispatched::add);
        long before = core.s2cDispatchedCount();

        OutboundConnection connA = establishActive(core); // 初始连接 resumeAccepted=false → 不回退
        assertEquals(0, dispatched.size(), "初始连接（resumeAccepted=false）不应回退");

        core.migrateTo(MASTER_B);
        assertEquals(NetworkCoreState.MIGRATING, core.state());
        OutboundConnection connB = created.get(0);
        acceptHandshake(connB, true);

        assertEquals(NetworkCoreState.ACTIVE, core.state());
        assertTrue(core.lastResumeAccepted(), "续流就绪");
        assertEquals(before + 1, core.s2cDispatchedCount(), "回退位置包应经 dispatchS2C 分发");
        assertEquals(1, dispatched.size(), "注入器应收到回退位置包");
        Packet<?> rollback = dispatched.get(0);
        assertTrue(rollback instanceof ClientboundPlayerPositionPacket, "回退包应为 ClientboundPlayerPositionPacket");
        // 位置 = 续流握手上报快照（installPlayerSources 注入值 (10,20,30) yaw=90 pitch=0）
        assertRollbackPosition((ClientboundPlayerPositionPacket) rollback, 10.0, 20.0, 30.0, 90.0f, 0.0f);

        core.unregisterS2CInjector(dispatched::add);
        core.onDisconnect();
    }

    /** B1：握手响应通告 controlEndpoints → applyHandshake 填充迁移候选；空列表不覆盖编程注入。 */
    @Test
    void handshakeAdvertisementFillsTargetEndpoints() {
        NetworkCore core = NetworkCore.getInstance();
        resetCore(core);
        List<OutboundConnection> created = new ArrayList<>();
        installEmbeddedFactory(core, created);
        installPlayerSources(core);
        // 编程注入兜底：先注入，握手通告为空时不得覆盖
        core.migration().setTargetEndpoints(List.of(MASTER_B));

        // 初始连接（S2C 尾 disabled → controlEndpoints 空）→ targets 保留编程注入
        OutboundConnection connA = establishActive(core);
        assertEquals(List.of(MASTER_B), core.migration().targetEndpoints(), "空通告不覆盖编程注入");

        // 迁移握手：S2C 尾带 controlEndpoints（udp=false 仍写 controls）→ targets 被通告填充
        core.migrateToImmediate(MASTER_B);
        assertEquals(NetworkCoreState.MIGRATING, core.state());
        assertEquals(1, created.size());
        OutboundConnection connB = core.outbound();
        EmbeddedChannel embeddedB = (EmbeddedChannel) connB.channel();
        ByteBuf response = HandshakeCodec.encodeServerResponse(1, true, true, true,
                new UdpDataPlaneHandshakeTail.S2CTail(
                        false, true, 7L, UdpDataPlaneHandshakeTail.PROTOCOL_VERSION, TOKEN,
                        List.of(new UdpDataPlaneHandshakeTail.ControlEndpoint("c.example", 25567, 100)),
                        List.of()),
                0L, null, false);
        HandshakeStateTail.writeS2C(response, new HandshakeStateTail.S2C(true));
        embeddedB.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_S2C, response));
        response.release();

        assertEquals(NetworkCoreState.ACTIVE, core.state());
        assertEquals(List.of(new MigrationEndpoint("c.example", 25567)),
                core.migration().targetEndpoints(), "握手通告端点池应填充迁移候选");

        core.onDisconnect();
    }
}
