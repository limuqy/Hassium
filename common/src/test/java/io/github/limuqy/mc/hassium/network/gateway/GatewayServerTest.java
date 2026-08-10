package io.github.limuqy.mc.hassium.network.gateway;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.ResumeTicket;
import io.github.limuqy.mc.hassium.network.ResumeTicketValidator;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameType;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主控侧网关接入层（T11）：帧连接握手（续流验票/resumeAccepted 尾）→ UUID-keyed
 * 玩家会话注册 → S2C 推送帧出站 / C2S 帧注入 → 断连清理。
 */
class GatewayServerTest {

    private final List<UUID> usedUuids = new ArrayList<>();

    /** 1.20.5+（1.21.2+ 的 bootstrap 检查机制）注册表访问需先 Bootstrap；缺失时首个
     *  触发 ChunkStatus 初始化的握手（真实 TCP/embedded 路径）抛 "Not bootstrapped"
     *  并污染 BuiltInRegistries 初始化缓存（连带 GatewaySmokeTest @BeforeAll 失败）。 */
#if MC_VER >= MC_1_20_5
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

    private static HandshakeCodec.ClientRequestOptions testOptions() {
        return new HandshakeCodec.ClientRequestOptions(
                Constants.CURRENT_PROTOCOL_VERSION, Constants.MOD_VERSION,
                Constants.NETWORK_COMPRESSION_ALGORITHM,
                true, true, 12.5, -34.0, false, true);
    }

    private static byte[] signedTicket(UUID playerId, long epoch) {
        byte[] sig = ResumeTicket.sign(playerId, epoch, ResumeTicket.sharedKey());
        return new ResumeTicket(playerId, epoch, sig).encode();
    }

    private static ByteBuf resumeHandshakePayload(long epoch) {
        UUID playerId = UUID.randomUUID();
        return resumeHandshakePayload(playerId, epoch, true);
    }

    private static ByteBuf resumeHandshakePayload(UUID playerId, long epoch, boolean resumeRequested) {
        ByteBuf buf = HandshakeCodec.encodeClientRequest(testOptions());
        if (resumeRequested) {
            HandshakeStateTail.writeC2S(buf, new HandshakeStateTail.C2S(
                    new PlayerStateReport(10.5, 64.0, 20.25, 90.0f, 0.0f, "minecraft:overworld"),
                    true, signedTicket(playerId, epoch), playerId, true));
        }
        return buf;
    }

    @BeforeEach
    void setUp() {
        GatewayServer server = GatewayServer.getInstance();
        server.setInfoProvider(null);   // 默认：接受握手、压缩/UDP/SeedGen 关闭
        server.setLoginSink(null);
    }

    @AfterEach
    void tearDown() {
        GatewayServer server = GatewayServer.getInstance();
        server.stop(); // 幂等
        server.registry().clear();
        for (UUID id : usedUuids) {
            ResumeTicketValidator.clear(id);
        }
        usedUuids.clear();
    }

    /** 续流握手：验票通过 → resumeAccepted=true → UUID-keyed 会话注册 + T7 推送分支触发。 */
    @Test
    void resumeHandshakeRegistersSessionAndAccepts() {
        GatewayServer server = GatewayServer.getInstance();
        UUID playerId = UUID.randomUUID();
        usedUuids.add(playerId);
        long epoch = 7L;

        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        EmbeddedChannel embedded = (EmbeddedChannel) channel.channel();
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_C2S,
                resumeHandshakePayload(playerId, epoch, true)));

        // S2C 响应：accepted + resumeAccepted 尾
        ByteBuf responseFrame = embedded.readOutbound();
        assertNotNull(responseFrame, "应已发出 HANDSHAKE_S2C");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(responseFrame);
            assertNotNull(frame);
            assertEquals(ControlFrameType.HANDSHAKE_S2C, frame.type());
            HandshakeCodec.ServerResponse resp = HandshakeCodec.decodeServerResponse(frame.payload());
            assertTrue(resp.accepted());
            assertTrue(HandshakeStateTail.readS2C(frame.payload()).resumeAccepted(),
                    "验票通过 → resumeAccepted=true");
            frame.payload().release();
        } finally {
            responseFrame.release();
        }

        // 玩家会话注册（UUID-keyed，日志可验证）
        GatewayPlayerSession session = server.registry().get(playerId);
        assertNotNull(session, "应注册 UUID-keyed 玩家会话");
        assertTrue(session.resume());
        assertEquals(epoch, session.resumeEpoch());
        assertTrue(channel.resumeAccepted());
        assertTrue(channel.state() == GatewayChannel.State.ACTIVE);
        assertTrue(channel.stateTail().lightComputeSupported(),
                "A7：帧握手尾 lightComputeSupported 应传递到会话（客户端能力 → 剥光 gate）");

        // T7 分支被帧侧触发：推送链续流标记 + 位置上报入库
        assertTrue(ServerChunkPushManager.getInstance().isPlayerResumeActive(playerId));
        assertEquals(epoch, ServerChunkPushManager.getInstance().playerResumeEpoch(playerId));
        PlayerStateReport reported = ServerChunkPushManager.getInstance().getPlayerStateReport(playerId);
        assertNotNull(reported);
        assertEquals(10.5, reported.x(), 0.0);
        assertEquals("minecraft:overworld", reported.dimension());

        channel.close("test");
        assertNull(server.registry().get(playerId), "断连清理会话");
        assertFalse(ServerChunkPushManager.getInstance().isPlayerResumeActive(playerId), "removePlayer 清理续流标记");
    }

    /** 票据重放/旧 epoch → resumeAccepted=false，不注册会话（回退新会话语义）。 */
    @Test
    void replayedTicketRejected() {
        GatewayServer server = GatewayServer.getInstance();
        UUID playerId = UUID.randomUUID();
        usedUuids.add(playerId);
        long epoch = 3L;
        ResumeTicketValidator.verifyAndAccept(new ResumeTicket(playerId, epoch,
                ResumeTicket.sign(playerId, epoch, ResumeTicket.sharedKey())));

        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        EmbeddedChannel embedded = (EmbeddedChannel) channel.channel();
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_C2S,
                resumeHandshakePayload(playerId, epoch, true)));

        ByteBuf responseFrame = embedded.readOutbound();
        assertNotNull(responseFrame);
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(responseFrame);
            assertNotNull(frame);
            HandshakeCodec.ServerResponse resp = HandshakeCodec.decodeServerResponse(frame.payload());
            assertTrue(resp.accepted());
            assertFalse(HandshakeStateTail.readS2C(frame.payload()).resumeAccepted(),
                    "同票重放 → resumeAccepted=false");
            frame.payload().release();
        } finally {
            responseFrame.release();
        }
        assertNull(server.registry().get(playerId), "验票失败不注册会话");
        assertFalse(ServerChunkPushManager.getInstance().isPlayerResumeActive(playerId));
        channel.close("test");
    }

    /** 新会话（无票据）：握手接受但 resumeAccepted=false，无会话注册（待登录桥附着）。 */
    @Test
    void freshHandshakeAcceptedWithoutSession() {
        GatewayServer server = GatewayServer.getInstance();
        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        EmbeddedChannel embedded = (EmbeddedChannel) channel.channel();
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_C2S,
                HandshakeCodec.encodeClientRequest(testOptions())));

        ByteBuf responseFrame = embedded.readOutbound();
        assertNotNull(responseFrame);
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(responseFrame);
            assertNotNull(frame);
            HandshakeCodec.ServerResponse resp = HandshakeCodec.decodeServerResponse(frame.payload());
            assertTrue(resp.accepted());
            assertFalse(HandshakeStateTail.readS2C(frame.payload()).resumeAccepted());
            frame.payload().release();
        } finally {
            responseFrame.release();
        }
        assertEquals(0, server.registry().size(), "无票据 → 不注册（登录桥 attachPlayer 附着）");

        // 登录桥附着路径
        UUID playerId = UUID.randomUUID();
        usedUuids.add(playerId);
        GatewayPlayerSession session = channel.attachPlayer(playerId, false, Long.MIN_VALUE, null);
        assertNotNull(session);
        assertEquals(session, server.registry().get(playerId));
        assertFalse(session.resume());

        channel.close("test");
        assertNull(server.registry().get(playerId));
    }

    /** 数据桥：S2C 推送帧出站可验证；C2S 帧注入会话 sink 可验证。 */
    @Test
    void s2cPushFramesOutAndC2SInjection() {
        GatewayServer server = GatewayServer.getInstance();
        UUID playerId = UUID.randomUUID();
        usedUuids.add(playerId);
        long epoch = 5L;
        // 服务端总数跨测试累计 → 用基线差值断言
        long s2cBase = server.s2cFramesTotal();
        long c2sBase = server.c2sFramesTotal();

        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        EmbeddedChannel embedded = (EmbeddedChannel) channel.channel();
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_C2S,
                resumeHandshakePayload(playerId, epoch, true)));
        ByteBuf responseFrame = embedded.readOutbound();
        assertNotNull(responseFrame);
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(responseFrame);
            assertNotNull(frame);
            frame.payload().release();
        } finally {
            responseFrame.release();
        }

        GatewayPlayerSession session = server.registry().get(playerId);
        assertNotNull(session);

        // S2C：区块/hash/delta 等原版包编码后 → PACKET_S2C 帧
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4});
        session.sendS2CPayload(payload);
        ByteBuf s2cFrameBuf = embedded.readOutbound();
        assertNotNull(s2cFrameBuf, "S2C 推送应发出 PACKET_S2C 帧");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(s2cFrameBuf);
            assertNotNull(frame);
            assertEquals(ControlFrameType.PACKET_S2C, frame.type());
            byte[] got = new byte[frame.payload().readableBytes()];
            frame.payload().readBytes(got);
            assertArrayEquals(new byte[] {1, 2, 3, 4}, got);
            frame.payload().release();
        } finally {
            s2cFrameBuf.release();
        }
        assertEquals(1, session.s2cFramesSent());
        assertEquals(1, server.s2cFramesTotal() - s2cBase);
        assertEquals(1, channel.s2cFramesSent());

        // C2S：T5 中继帧 → 注入玩家会话处理链（sink）
        AtomicReference<UUID> seenPlayer = new AtomicReference<>();
        AtomicReference<byte[]> seenPayload = new AtomicReference<>();
        session.setC2SSink((pid, buf) -> {
            seenPlayer.set(pid);
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            seenPayload.set(b);
            buf.release();
        });
        ByteBuf c2sPayload = Unpooled.wrappedBuffer(new byte[] {9, 9, 9});
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.PACKET_C2S, c2sPayload));
        assertEquals(playerId, seenPlayer.get(), "C2S 帧应注入对应玩家会话");
        assertArrayEquals(new byte[] {9, 9, 9}, seenPayload.get());
        assertEquals(1, channel.c2sFramesReceived());
        assertEquals(1, server.c2sFramesTotal() - c2sBase);

        channel.close("test");
    }

    /** 真实 TCP 接入（帧连接建立）：bind → accept → 握手 → 会话注册全链路。 */
    @Test
    void realTcpAcceptRegistersSession() throws Exception {
        GatewayServer server = GatewayServer.getInstance();
        server.start("127.0.0.1", 0);
        try {
            // 等待异步 bind 完成并取实际端口
            long deadline = System.currentTimeMillis() + 5000;
            int port = -1;
            while (System.currentTimeMillis() < deadline) {
                io.netty.channel.Channel sc = server.serverChannel();
                if (sc != null && sc.localAddress() instanceof InetSocketAddress isa && isa.getPort() > 0) {
                    port = isa.getPort();
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(port > 0, "gateway 应完成绑定");
            assertTrue(server.isRunning());
            assertTrue(server.connectionCount() >= 0);

            UUID playerId = UUID.randomUUID();
            usedUuids.add(playerId);
            long epoch = 11L;
            ByteBuf handshake = ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_C2S,
                    resumeHandshakePayload(playerId, epoch, true));
            try (Socket sock = new Socket("127.0.0.1", port)) {
                sock.setSoTimeout(5000);
                byte[] frameBytes = new byte[handshake.readableBytes()];
                handshake.readBytes(frameBytes);
                sock.getOutputStream().write(frameBytes);
                sock.getOutputStream().flush();

                // 读 HANDSHAKE_S2C 响应帧
                ByteBuf in = Unpooled.buffer();
                ControlFrameCodec.Frame resp = null;
                long readDeadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < readDeadline) {
                    byte[] chunk = new byte[256];
                    int n = sock.getInputStream().read(chunk);
                    if (n > 0) {
                        in.writeBytes(chunk, 0, n);
                        resp = ControlFrameCodec.tryDecodeFrame(in);
                        if (resp != null) {
                            break;
                        }
                    } else if (n < 0) {
                        break;
                    }
                }
                try {
                    assertNotNull(resp, "应收到 HANDSHAKE_S2C 响应帧");
                    HandshakeCodec.ServerResponse sr = HandshakeCodec.decodeServerResponse(resp.payload());
                    assertTrue(sr.accepted());
                    assertTrue(HandshakeStateTail.readS2C(resp.payload()).resumeAccepted());
                } finally {
                    if (resp != null) {
                        resp.payload().release();
                    }
                    in.release();
                }
                assertEquals(1, server.connectionCount(), "帧连接应登记");
                GatewayPlayerSession session = server.registry().get(playerId);
                assertNotNull(session, "帧连接建立 → 玩家会话注册（UUID-keyed）");
                assertTrue(session.resume());
                assertEquals(epoch, session.resumeEpoch());
                assertTrue(ServerChunkPushManager.getInstance().isPlayerResumeActive(playerId));
            }
        } finally {
            server.stop();
            assertFalse(server.isRunning());
            assertEquals(0, server.registry().size(), "停机清理全部会话");
        }
    }

    /** LOGIN_C2S 帧（T5 帧类型 9）：登录阶段分发到登录桥缝。 */
    @Test
    void loginFramesRouteToLoginSink() {
        GatewayServer server = GatewayServer.getInstance();
        AtomicReference<byte[]> seen = new AtomicReference<>();
        AtomicReference<GatewayChannel> seenChannel = new AtomicReference<>();
        server.setLoginSink((channel, buf) -> {
            seenChannel.set(channel);
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            seen.set(b);
            buf.release();
        });

        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        EmbeddedChannel embedded = (EmbeddedChannel) channel.channel();
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_C2S,
                HandshakeCodec.encodeClientRequest(testOptions())));
        ByteBuf resp = embedded.readOutbound();
        assertNotNull(resp);
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(resp);
            assertNotNull(frame);
            frame.payload().release();
        } finally {
            resp.release();
        }

        ByteBuf loginPayload = Unpooled.wrappedBuffer(new byte[] {5, 6, 7});
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.LOGIN_C2S, loginPayload));
        assertEquals(channel, seenChannel.get());
        assertArrayEquals(new byte[] {5, 6, 7}, seen.get());
        assertEquals(1, channel.loginFramesReceived());
        assertEquals(1, server.loginFramesTotal());

        channel.close("test");
    }

    /** T10 标准流程：非续流握手携带 playerId → 会话登记（resume=false），待 vanilla 物化挂 sink。 */
    @Test
    void identHandshakeRegistersNonResumeSession() {
        GatewayServer server = GatewayServer.getInstance();
        UUID playerId = UUID.randomUUID();
        usedUuids.add(playerId);

        ByteBuf payload = HandshakeCodec.encodeClientRequest(testOptions());
        HandshakeStateTail.writeC2S(payload, HandshakeStateTail.C2S.ident(
                new PlayerStateReport(1.0, 2.0, 3.0, 4.0f, 5.0f, "minecraft:overworld"), playerId));

        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        EmbeddedChannel embedded = (EmbeddedChannel) channel.channel();
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_C2S, payload));

        ByteBuf responseFrame = embedded.readOutbound();
        assertNotNull(responseFrame);
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(responseFrame);
            assertNotNull(frame);
            HandshakeCodec.ServerResponse resp = HandshakeCodec.decodeServerResponse(frame.payload());
            assertTrue(resp.accepted());
            assertFalse(HandshakeStateTail.readS2C(frame.payload()).resumeAccepted(),
                    "非续流握手 → resumeAccepted=false");
            frame.payload().release();
        } finally {
            responseFrame.release();
        }

        GatewayPlayerSession session = server.registry().get(playerId);
        assertNotNull(session, "标准流程握手身份 → 会话登记");
        assertFalse(session.resume());
        assertEquals(Long.MIN_VALUE, session.resumeEpoch());
        assertFalse(ServerChunkPushManager.getInstance().isPlayerResumeActive(playerId),
                "非续流不触发续流标记");

        channel.close("test");
        assertNull(server.registry().get(playerId), "断连清理会话");
    }

    /** CONFIG_C2S 帧（T10 帧类型 11）：配置阶段分发到登录桥缝（acceptConfig 默认走 accept）。 */
    @Test
    void configFramesRouteToLoginSink() {
        GatewayServer server = GatewayServer.getInstance();
        AtomicReference<byte[]> seen = new AtomicReference<>();
        AtomicReference<GatewayChannel> seenChannel = new AtomicReference<>();
        server.setLoginSink((channel, buf) -> {
            seenChannel.set(channel);
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            seen.set(b);
            buf.release();
        });

        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        EmbeddedChannel embedded = (EmbeddedChannel) channel.channel();
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_C2S,
                HandshakeCodec.encodeClientRequest(testOptions())));
        ByteBuf resp = embedded.readOutbound();
        assertNotNull(resp);
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(resp);
            assertNotNull(frame);
            frame.payload().release();
        } finally {
            resp.release();
        }

        ByteBuf configPayload = Unpooled.wrappedBuffer(new byte[] {8, 9, 10});
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.CONFIG_C2S, configPayload));
        assertEquals(channel, seenChannel.get());
        assertArrayEquals(new byte[] {8, 9, 10}, seen.get());
        assertEquals(1, channel.configFramesReceived());

        channel.close("test");
    }
}
