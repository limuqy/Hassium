package io.github.limuqy.mc.hassium.network.gateway;

import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.ResumeTicket;
import io.github.limuqy.mc.hassium.network.ResumeTicketValidator;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec;
import io.github.limuqy.mc.hassium.network.core.NetworkCore;
import io.github.limuqy.mc.hassium.network.core.NetworkCoreState;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T10 端到端冒烟（骨架级，真实 TCP 双端）：
 * 客户端 NetworkCore.connect → 主控 GatewayServer 握手 accepted → 会话注册 →
 * S2C 推送帧 → 客户端注入计数（s2cDispatchedCount）→ C2S routeC2S 计数
 * （c2sRoutedCount + 主控 c2sFramesReceived）→ 续流票据路径（resumeAccepted +
 * [RESUME] 日志）。验收 = 计数/日志，不要求完整 gameplay S2C。
 */
class GatewaySmokeTest {

    private final List<UUID> usedUuids = new ArrayList<>();

    /** 编解码器静态初始化依赖注册表 bootstrap（同 GatewayPacketCodecTest）。 */
#if MC_VER >= MC_1_21_1
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
#endif

    @BeforeAll
    static void setupResumeKey() {
        ResumeTicket.setSharedKey("hassium-test-key".getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        GatewayServer server = GatewayServer.getInstance();
        server.setInfoProvider(null); // 默认：接受握手、压缩/UDP/SeedGen 关闭
        server.setLoginSink(null);
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        // 防其他测试类残留（迁移测试可能把 connectionFactory 留在 embedded 缝）
        core.migration().setConnectionFactory(null);
        core.migration().setPlayerIdSource(null);
        core.migration().setPlayerStateSource(null);
        core.migration().setTargetEndpoints(java.util.List.of());
        core.migration().setPolicy(io.github.limuqy.mc.hassium.network.core.migration.MigrationPolicy.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        NetworkCore.getInstance().onDisconnect();
        GatewayServer server = GatewayServer.getInstance();
        server.stop(); // 幂等
        server.registry().clear(); // 逐会话清理（含 ServerChunkPushManager.removePlayer 续流标记级联）
        for (UUID id : usedUuids) {
            ResumeTicketValidator.clear(id);
        }
        usedUuids.clear();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitTrue(BooleanSupplier cond, long timeoutMs, String what) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    /** PLAY CLIENTBOUND 简单包（全段构造稳定；S2C 推送帧用）。 */
    private static ByteBuf s2cPlayPayload() {
        // 1.21.2+ 改名 ClientboundSetHeldSlotPacket（int 构造保留）
#if MC_VER < MC_1_21_2
        Packet<?> packet = new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(7);
#else
        Packet<?> packet = new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(7);
#endif
        return GatewayPacketCodec.encodeVanilla(
                packet, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.PLAY, RegistryAccess.EMPTY);
    }

    /** PLAY SERVERBOUND 简单包（全段构造稳定；routeC2S 用）。 */
    private static Packet<?> playC2SPacket() {
#if MC_VER < MC_1_21_1
        return new net.minecraft.network.protocol.game.ServerboundKeepAlivePacket(123L);
#else
        return new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(123L);
#endif
    }

    private static byte[] signedTicket(UUID playerId, long epoch) {
        byte[] sig = ResumeTicket.sign(playerId, epoch, ResumeTicket.sharedKey());
        return new ResumeTicket(playerId, epoch, sig).encode();
    }

    /** 标准流程冒烟：握手（非续流 + playerId）→ ACTIVE → 会话注册 → S2C 推送 → C2S 路由。 */
    @Test
    void endToEndStandardFlow() throws Exception {
        GatewayServer server = GatewayServer.getInstance();
        int port = freePort();
        server.start(port);
        awaitTrue(() -> awaitServerUp(port), 5000, "gateway listening");

        UUID playerId = UUID.randomUUID();
        usedUuids.add(playerId);
        NetworkCore core = NetworkCore.getInstance();
        HandshakeStateTail.C2S tail = new HandshakeStateTail.C2S(
                new PlayerStateReport(10.5, 64.0, 20.25, 90.0f, 0.0f, "minecraft:overworld"),
                false, null, playerId, true);
        core.connect("127.0.0.1", port, tail);

        // 握手 accepted → ACTIVE（客户端）
        awaitTrue(() -> core.state() == NetworkCoreState.ACTIVE, 10_000, "client ACTIVE");
        assertFalse(core.lastResumeAccepted(), "非续流握手 → resumeAccepted=false");

        // 会话注册（主控侧；attach 钩子 → 无 vanilla 服务器 → pending/跳过不炸）
        awaitTrue(() -> server.registry().get(playerId) != null, 10_000, "session registered");
        GatewayPlayerSession session = server.registry().get(playerId);
        assertNotNull(session);
        assertFalse(session.resume());
        assertEquals(Long.MIN_VALUE, session.resumeEpoch());

        // S2C 推送：主控 → 客户端注入计数
        long beforeS2C = core.s2cDispatchedCount();
        ByteBuf payload = s2cPlayPayload();
        session.sendS2CPayload(payload);
        awaitTrue(() -> core.s2cDispatchedCount() >= beforeS2C + 1, 10_000,
                "client s2cDispatchedCount >= " + (beforeS2C + 1));
        assertTrue(session.s2cFramesSent() >= 1, "会话 S2C 帧计数");

        // C2S 路由：客户端 → 主控帧计数
        long beforeC2S = session.channel().c2sFramesReceived();
        boolean routed = core.routeC2S(playC2SPacket());
        assertTrue(routed, "ACTIVE + outbound open → 应完整编码并路由");
        assertEquals(1, core.c2sRoutedCount(), "routeC2S 计数可验证");
        awaitTrue(() -> session.channel().c2sFramesReceived() >= beforeC2S + 1, 10_000,
                "master c2sFramesReceived");
    }

    /** 续流冒烟：票据握手 → resumeAccepted=true → 会话 resume + [RESUME] 推送链标记。 */
    @Test
    void resumeFlowThroughRealTcp() throws Exception {
        GatewayServer server = GatewayServer.getInstance();
        int port = freePort();
        server.start(port);
        awaitTrue(() -> awaitServerUp(port), 5000, "gateway listening");

        UUID playerId = UUID.randomUUID();
        usedUuids.add(playerId);
        long epoch = 5L;
        NetworkCore core = NetworkCore.getInstance();
        HandshakeStateTail.C2S tail = new HandshakeStateTail.C2S(
                new PlayerStateReport(10.5, 64.0, 20.25, 90.0f, 0.0f, "minecraft:overworld"),
                true, signedTicket(playerId, epoch), playerId, true);
        core.connect("127.0.0.1", port, tail);

        awaitTrue(() -> core.state() == NetworkCoreState.ACTIVE, 10_000, "client ACTIVE");
        awaitTrue(() -> core.lastResumeAccepted(), 10_000, "client resumeAccepted=true");

        awaitTrue(() -> server.registry().get(playerId) != null, 10_000, "session registered");
        GatewayPlayerSession session = server.registry().get(playerId);
        assertNotNull(session);
        assertTrue(session.resume());
        assertEquals(epoch, session.resumeEpoch());

        // T7 推送链标记（[RESUME] 日志源）
        awaitTrue(() -> ServerChunkPushManager.getInstance().isPlayerResumeActive(playerId), 10_000,
                "resume active (push chain)");
        assertEquals(epoch, ServerChunkPushManager.getInstance().playerResumeEpoch(playerId));

        // 续流会话同样可推 S2C
        long beforeS2C = core.s2cDispatchedCount();
        ByteBuf payload = s2cPlayPayload();
        session.sendS2CPayload(payload);
        awaitTrue(() -> core.s2cDispatchedCount() >= beforeS2C + 1, 10_000, "client s2cDispatchedCount");
    }

    private static boolean awaitServerUp(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
