package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameType;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.OutboundConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录桥接（中继）与 S2C 注入路径（T5）：
 * 登录 C2S → LOGIN_C2S 帧；LOGIN_S2C/PACKET_S2C 帧 → dispatchS2C 注入计数。
 */
class NetworkCoreLoginRelayTest {

    /** 编解码器静态初始化依赖注册表 bootstrap（同 GatewayPacketCodecTest）。 */
#if MC_VER >= MC_1_20_5
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
#endif

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

    private static HandshakeCodec.ClientRequestOptions testOptions() {
        return new HandshakeCodec.ClientRequestOptions(
                Constants.CURRENT_PROTOCOL_VERSION, Constants.MOD_VERSION,
                Constants.NETWORK_COMPRESSION_ALGORITHM,
                true, true, 12.5, -34.0, false, true);
    }

    private static NetworkCore freshCore(OutboundConnection[] out) {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect(); // 重置（单例跨测试共享）
        OutboundConnection conn = OutboundConnection.openEmbedded(testOptions(), core);
        core.attach(conn);
        out[0] = conn;
        return core;
    }

    /** 真实登录阶段 C2S 包（两段构造稳定：1.20.1 CustomQuery / 1.20.2+ CustomQueryAnswer）。 */
    private static Packet<?> realLoginC2SPacket() {
#if MC_VER < MC_1_20_2
        // 1.20.1（Forge 合并版）构造为 (int, FriendlyByteBuf)
        return new net.minecraft.network.protocol.login.ServerboundCustomQueryPacket(
                1, (net.minecraft.network.FriendlyByteBuf) null);
#else
        return new net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket(1, null);
#endif
    }

    @Test
    void routeLoginC2SEncodesLoginFrame() {
        OutboundConnection[] out = new OutboundConnection[1];
        NetworkCore core = freshCore(out);
        EmbeddedChannel embedded = (EmbeddedChannel) out[0].channel();
        embedded.readOutbound(); // 丢弃握手帧

        core.routeLoginC2S(realLoginC2SPacket());
        assertEquals(1, core.loginRelayedCount());

        ByteBuf frameBuf = embedded.readOutbound();
        assertNotNull(frameBuf, "登录 C2S 应编码进 outbound");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(frameBuf);
            assertNotNull(frame);
            assertEquals(ControlFrameType.LOGIN_C2S, frame.type());
            assertTrue(frame.payload().readableBytes() > 0);
            frame.payload().release();
        } finally {
            frameBuf.release();
        }
        core.onDisconnect();
    }

    @Test
    void relayLoginPacketFiltersNonLoginPackets() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        long before = core.loginRelayedCount();

        // 非登录包（fake + S2C 登录响应）不中继
        core.relayLoginPacket(fakePacket());
        core.relayLoginPacket(new ClientboundLoginDisconnectPacket(Component.literal("x")));
        assertEquals(before, core.loginRelayedCount(), "非登录 C2S 包不得计数");

        assertFalse(NetworkCore.isLoginPacket(fakePacket()));
        assertFalse(NetworkCore.isLoginPacket(new ClientboundLoginDisconnectPacket(Component.literal("x"))));
        // 真实登录 C2S 包：识别 + 中继计数（outbound 未开仅计数不吞包）
        assertTrue(NetworkCore.isLoginPacket(realLoginC2SPacket()));
        core.relayLoginPacket(realLoginC2SPacket());
        assertEquals(before + 1, core.loginRelayedCount(), "真实登录 C2S 包应计数");
    }

    @Test
    void loginS2CFrameDispatchesThroughInjector() {
        OutboundConnection[] out = new OutboundConnection[1];
        NetworkCore core = freshCore(out);
        EmbeddedChannel embedded = (EmbeddedChannel) out[0].channel();
        embedded.readOutbound();

        long before = core.s2cDispatchedCount();
        // 主控登录响应（LOGIN 协议原版包）经注入器分发（空环境降级日志，计数增长可验证）
        ByteBuf payload = GatewayPacketCodec.encodeVanilla(
                new ClientboundLoginDisconnectPacket(Component.literal("master-relay")),
                PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.LOGIN, RegistryAccess.EMPTY);
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.LOGIN_S2C, payload));
        payload.release();

        assertEquals(before + 1, core.s2cDispatchedCount(), "LOGIN_S2C 应经注入器分发");
        core.onDisconnect();
    }

    @Test
    void playS2CFrameDispatchesVanillaAndBusiness() {
        OutboundConnection[] out = new OutboundConnection[1];
        NetworkCore core = freshCore(out);
        EmbeddedChannel embedded = (EmbeddedChannel) out[0].channel();
        embedded.readOutbound();

        long before = core.s2cDispatchedCount();

        // kind=0 原版 PLAY 包
        // 1.21.2+ 改名 ClientboundSetHeldSlotPacket（int 构造保留）
#if MC_VER < MC_1_21_2
        ByteBuf vanilla = GatewayPacketCodec.encodeVanilla(
                new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(3),
                PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.PLAY, RegistryAccess.EMPTY);
#else
        ByteBuf vanilla = GatewayPacketCodec.encodeVanilla(
                new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(3),
                PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.PLAY, RegistryAccess.EMPTY);
#endif
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.PACKET_S2C, vanilla));
        vanilla.release();

        // kind=1 Hassium 业务包（chunkHash → ClientMetadataHandler 现有收口；空环境内玩家检查安全跳过）
        ChunkHashS2CPacket hash = new ChunkHashS2CPacket("minecraft:overworld",
                List.of(new ChunkHashS2CPacket.Entry(0, 0, 42L, 1)));
        ByteBuf business = Unpooled.buffer();
        try {
            business.writeByte(GatewayPacketCodec.KIND_HASSIUM);
            business.writeByte(GatewayPacketCodec.HassiumSub.CHUNK_HASH.id());
            hash.encode(new FriendlyByteBuf(business));
            embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.PACKET_S2C, business));
        } finally {
            business.release();
        }

        assertEquals(before + 2, core.s2cDispatchedCount(), "原版包与业务包都应计数");
        core.onDisconnect();
    }

#if MC_VER >= MC_1_20_2
    /** 真实配置阶段 C2S 包（1.20.2–1.20.4 record 无参构造；1.20.5+ INSTANCE）。 */
    private static Packet<?> realConfigC2SPacket() {
#if MC_VER < MC_1_20_5
        return new net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket();
#else
        return net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket.INSTANCE;
#endif
    }

    @Test
    void routeConfigC2SEncodesConfigFrame() {
        OutboundConnection[] out = new OutboundConnection[1];
        NetworkCore core = freshCore(out);
        EmbeddedChannel embedded = (EmbeddedChannel) out[0].channel();
        embedded.readOutbound(); // 丢弃握手帧

        core.routeConfigC2S(realConfigC2SPacket());
        assertEquals(1, core.configRelayedCount());

        ByteBuf frameBuf = embedded.readOutbound();
        assertNotNull(frameBuf, "配置 C2S 应编码进 outbound");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(frameBuf);
            assertNotNull(frame);
            assertEquals(ControlFrameType.CONFIG_C2S, frame.type());
            assertTrue(frame.payload().readableBytes() > 0);
            frame.payload().release();
        } finally {
            frameBuf.release();
        }
        core.onDisconnect();
    }

    @Test
    void relayConfigPacketFiltersNonConfigPackets() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        long before = core.configRelayedCount();

        // 非配置包（fake + 登录 C2S + S2C 响应）不中继
        core.relayConfigPacket(fakePacket());
        core.relayConfigPacket(realLoginC2SPacket());
        core.relayConfigPacket(new ClientboundLoginDisconnectPacket(Component.literal("x")));
        assertEquals(before, core.configRelayedCount(), "非配置 C2S 包不得计数");

        assertFalse(NetworkCore.isConfigPacket(fakePacket()));
        assertTrue(NetworkCore.isConfigPacket(realConfigC2SPacket()));
        // 真实配置 C2S 包：识别 + 中继计数（outbound 未开仅计数不吞包）
        core.relayConfigPacket(realConfigC2SPacket());
        assertEquals(before + 1, core.configRelayedCount(), "真实配置 C2S 包应计数");
    }

    @Test
    void configS2CFrameDispatchesThroughInjector() {
        OutboundConnection[] out = new OutboundConnection[1];
        NetworkCore core = freshCore(out);
        EmbeddedChannel embedded = (EmbeddedChannel) out[0].channel();
        embedded.readOutbound();

        long before = core.s2cDispatchedCount();
        // 主控配置响应（CONFIG 协议 CLIENTBOUND 包）经注入器分发（配置监听器缺省时降级日志，计数可验证）
#if MC_VER < MC_1_20_5
        ByteBuf payload = GatewayPacketCodec.encodeVanilla(
                new net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket(),
                PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.CONFIG, RegistryAccess.EMPTY);
#else
        ByteBuf payload = GatewayPacketCodec.encodeVanilla(
                net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket.INSTANCE,
                PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.CONFIG, RegistryAccess.EMPTY);
#endif
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.CONFIG_S2C, payload));
        payload.release();

        assertEquals(before + 1, core.s2cDispatchedCount(), "CONFIG_S2C 应经注入器分发");
        core.onDisconnect();
    }
#endif
}
