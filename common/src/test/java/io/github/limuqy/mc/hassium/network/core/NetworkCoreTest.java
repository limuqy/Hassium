package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.Constants;
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
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NetworkCore 单例：状态机 / 生命周期 / 握手流 / 入站 S2C 注入 / 出站 C2S 编码进 outbound。
 */
class NetworkCoreTest {

    private static final byte[] TOKEN = new byte[16];

    private static Packet<ServerGamePacketListener> fakePacket() {
        return new Packet<>() {
#if MC_VER < MC_1_21_1
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

    private static HandshakeCodec.ClientRequestOptions testOptions() {
        return new HandshakeCodec.ClientRequestOptions(
                Constants.CURRENT_PROTOCOL_VERSION, Constants.MOD_VERSION,
                Constants.NETWORK_COMPRESSION_ALGORITHM,
                true, true, 12.5, -34.0, false, true);
    }

    @Test
    void lifecycleStateTransitions() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect(); // 重置（单例跨测试共享）
        assertEquals(NetworkCoreState.IDLE, core.state());

        core.onLogin();
        assertEquals(NetworkCoreState.CONNECTING, core.state());

        core.onDisconnect();
        assertEquals(NetworkCoreState.IDLE, core.state());

        // MIGRATING 只能从 ACTIVE 进入
        assertFalse(core.transition(NetworkCoreState.ACTIVE, NetworkCoreState.MIGRATING));
        assertTrue(core.transition(NetworkCoreState.IDLE, NetworkCoreState.MIGRATING));
        assertEquals(NetworkCoreState.MIGRATING, core.state());
        core.onDisconnect();
        assertEquals(NetworkCoreState.IDLE, core.state());
    }

    @Test
    void handshakeFlowThroughEmbeddedOutbound() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        core.onLogin();
        assertEquals(NetworkCoreState.CONNECTING, core.state());

        // 构造期 channelActive → 自动发握手 + onOpen
        OutboundConnection conn = OutboundConnection.openEmbedded(testOptions(), core);
        core.attach(conn);
        assertEquals(NetworkCoreState.HANDSHAKING, core.state());
        assertTrue(conn.isOpen());

        // outbound 缓冲里应有 HANDSHAKE_C2S 帧，payload = encodeClientRequest(options)
        EmbeddedChannel embedded = (EmbeddedChannel) conn.channel();
        ByteBuf handshakeFrame = embedded.readOutbound();
        assertNotNull(handshakeFrame, "应已发出握手帧");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(handshakeFrame);
            assertNotNull(frame);
            assertEquals(ControlFrameType.HANDSHAKE_C2S, frame.type());
            ByteBuf expect = HandshakeCodec.encodeClientRequest(testOptions());
            try {
                assertEquals(expect.readableBytes(), frame.payload().readableBytes());
                byte[] got = new byte[frame.payload().readableBytes()];
                frame.payload().readBytes(got);
                byte[] want = new byte[expect.readableBytes()];
                expect.readBytes(want);
                assertArrayEquals(want, got);
            } finally {
                expect.release();
                frame.payload().release();
            }
        } finally {
            handshakeFrame.release();
        }

        // 灌入 S2C 握手响应（UDP tail + SeedGen 尾）→ ACTIVE
        UdpDataPlaneHandshakeTail.S2CTail tail = new UdpDataPlaneHandshakeTail.S2CTail(
                true, true, 9L, 1, TOKEN, List.of(), List.of(), List.of());
        ByteBuf response = HandshakeCodec.encodeServerResponse(
                1, true, true, true, tail, 12345L, new byte[] {1}, true);
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_S2C, response));
        response.release();

        assertEquals(NetworkCoreState.ACTIVE, core.state());
        assertNotNull(core.lastHandshake());
        assertTrue(core.lastHandshake().accepted());
        assertEquals(12345L, core.lastHandshake().worldSeed());
        assertTrue(core.lastHandshake().udpTail().hasUdpDataplane());

        // 断连 → IDLE，outbound 关闭
        core.onDisconnect();
        assertEquals(NetworkCoreState.IDLE, core.state());
        assertNull(core.outbound());
        assertFalse(conn.isOpen());
    }

    @Test
    void onLoginKeepsActiveBootstrapOutbound() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        core.onLogin();
        assertEquals(NetworkCoreState.CONNECTING, core.state());

        // 模拟 bootstrap：握手在 handleLogin 前完成（1.20.1 custom payload 先于主线程到达）
        OutboundConnection conn = OutboundConnection.openEmbedded(testOptions(), core);
        core.attach(conn);
        UdpDataPlaneHandshakeTail.S2CTail tail = new UdpDataPlaneHandshakeTail.S2CTail(
                true, true, 1L, 1, TOKEN, List.of(), List.of(), List.of());
        ByteBuf response = HandshakeCodec.encodeServerResponse(
                1, true, true, true, tail, 12345L, new byte[] {1}, true);
        ((EmbeddedChannel) conn.channel()).writeInbound(
                ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_S2C, response));
        response.release();
        assertEquals(NetworkCoreState.ACTIVE, core.state());
        // L2 场景：pre-login 已 dispatch 的 S2C（1.21.1 R1 冒烟：ACTIVE 与 onLogin 间服务端
        // 已推 chunk hash）——计数在 onLogin 后必须保留
        core.dispatchS2C(fakePacket());
        core.dispatchS2C(fakePacket());
        assertEquals(2, core.s2cDispatchedCount());
        // 对照：其他周期计数（c2sRouted）onLogin 后仍复位（语义不变）
        core.routeC2S(fakePacket());
        assertEquals(1, core.c2sRoutedCount());

        // 修复：bootstrap 已 ACTIVE 时 onLogin 保持连接（不 close、不重连、不降 CONNECTING）
        core.onLogin();
        assertEquals(NetworkCoreState.ACTIVE, core.state());
        assertSame(conn, core.outbound());
        assertTrue(conn.isOpen());
        assertEquals(2, core.s2cDispatchedCount(), "bootstrap onLogin 不得清掉 pre-login dispatch 计数");
        assertEquals(0, core.c2sRoutedCount(), "其他计数器语义不变：c2sRouted 仍复位");


        core.onDisconnect();
        assertNull(core.outbound());
    }
    @Test
    void onLoginNewConnectionResetsS2cCount() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();

        // 无活连接（新连接分支）：onLogin 全量复位，s2cDispatched 一并清零
        core.dispatchS2C(fakePacket());
        assertEquals(1, core.s2cDispatchedCount());

        core.onLogin();
        assertEquals(NetworkCoreState.CONNECTING, core.state());
        assertEquals(0, core.s2cDispatchedCount(), "新连接分支应全量复位（含 s2cDispatched）");

        core.onDisconnect();
    }

    @Test
    void dispatchS2CCountsAndFansOutToInjectors() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();

        Packet<ServerGamePacketListener> fake = fakePacket();
        AtomicInteger seen = new AtomicInteger();
        java.util.function.Consumer<Packet<?>> injector = p -> seen.incrementAndGet();
        core.registerS2CInjector(injector);

        core.dispatchS2C(fake);
        core.dispatchS2C(fake);

        assertEquals(2, core.s2cDispatchedCount());
        assertEquals(2, seen.get());

        core.unregisterS2CInjector(injector);
        core.dispatchS2C(fake);
        assertEquals(3, core.s2cDispatchedCount());
        assertEquals(2, seen.get(), "注销后不应再收到");
    }

    @Test
    void routeC2SEncodesIntoOutbound() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        core.onLogin();

        OutboundConnection conn = OutboundConnection.openEmbedded(testOptions(), core);
        core.attach(conn);
        // T10：routeC2S 仅在 ACTIVE 路由（CONNECTING/HANDSHAKING 原版直连兜底）；
        // attach 触发 onOpen 可能已推进到 HANDSHAKING
        if (!core.transition(NetworkCoreState.CONNECTING, NetworkCoreState.ACTIVE)) {
            assertTrue(core.transition(NetworkCoreState.HANDSHAKING, NetworkCoreState.ACTIVE));
        }
        EmbeddedChannel embedded = (EmbeddedChannel) conn.channel();
        embedded.readOutbound(); // 丢弃握手帧

        NetworkCore.C2SEncoder saved = core.c2sEncoder();
        core.setC2SEncoder(p -> Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));
        core.routeC2S(fakePacket());
        assertEquals(1, core.c2sRoutedCount());

        ByteBuf frameBuf = embedded.readOutbound();
        assertNotNull(frameBuf, "C2S 应编码进 outbound");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(frameBuf);
            assertNotNull(frame);
            assertEquals(ControlFrameType.PACKET_C2S, frame.type());
            byte[] got = new byte[frame.payload().readableBytes()];
            frame.payload().readBytes(got);
            assertArrayEquals(new byte[] {1, 2, 3}, got);
            frame.payload().release();
        } finally {
            frameBuf.release();
        }

        // 无编码器：仅计数
        core.setC2SEncoder(null);
        core.routeC2S(fakePacket());
        assertEquals(2, core.c2sRoutedCount());

        // 恢复真实编码器（单例跨测试共享；防后续测试拿到 null 编码器）
        core.setC2SEncoder(saved);
        core.onDisconnect();
    }

    @Test
    void migrateThenAcceptReturnsToActive() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        core.onLogin();

        // 直接推到 ACTIVE（真实 connect 走 embedded 之外的异步路径，此处用 CAS 模拟会话建立）
        assertTrue(core.transition(NetworkCoreState.CONNECTING, NetworkCoreState.ACTIVE));
        assertTrue(core.transition(NetworkCoreState.ACTIVE, NetworkCoreState.MIGRATING));
        assertEquals(NetworkCoreState.MIGRATING, core.state());

        UdpDataPlaneHandshakeTail.S2CTail tail = new UdpDataPlaneHandshakeTail.S2CTail(
                false, false, 1L, 1, TOKEN, List.of(), List.of(), List.of());
        HandshakeCodec.ServerResponse resp = HandshakeCodec.decodeServerResponse(
                HandshakeCodec.encodeServerResponse(1, true, true, true, tail, 0L, null, false));
        core.onHandshakeAccepted(resp);
        assertEquals(NetworkCoreState.ACTIVE, core.state());

        core.onDisconnect();
    }
}
