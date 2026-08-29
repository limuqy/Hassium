package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关帧 payload 编解码：原版包（PLAY/LOGIN，kind=0）往返 + Hassium 业务包（kind=1）解码。
 */
class GatewayPacketCodecTest {

    /** GameProtocols/LoginProtocols 静态初始化依赖注册表 bootstrap；1.20.1 段走 ConnectionProtocol 静态表，无需。 */
#if MC_VER >= MC_1_21_1
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
#endif

    @Test
    void vanillaPlayPacketRoundtrip() {
        // 简单 PLAY CLIENTBOUND 包（全段构造稳定，无注册表内容）
        // 1.21.2+ 改名 ClientboundSetHeldSlotPacket（int 构造保留）
#if MC_VER < MC_1_21_2
        Packet<?> packet = new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(7);
#else
        Packet<?> packet = new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(7);
#endif
        ByteBuf payload = GatewayPacketCodec.encodeVanilla(
                packet, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.PLAY, RegistryAccess.EMPTY);
        try {
            assertEquals(GatewayPacketCodec.KIND_VANILLA, GatewayPacketCodec.peekKind(payload));
            Packet<?> decoded = GatewayPacketCodec.decodeVanilla(
                    payload, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.PLAY, RegistryAccess.EMPTY);
            // vanilla packet 在 1.21.2 前无 equals（非 record），按字段断言。
            // 1.21.2-1.21.3 普通类 getSlot()，1.21.4+ record slot()——1.21.4 为白名单外
            // 碎片段（无段常量），测试内反射取 accessor（getSlot 优先，record 回退 slot）。
            int slot;
            try {
                slot = (Integer) decoded.getClass().getMethod("getSlot").invoke(decoded);
            } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException noPlainGetter) {
                try {
                    slot = (Integer) decoded.getClass().getMethod("slot").invoke(decoded);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("no slot accessor on " + decoded.getClass(), e);
                }
            }
            assertEquals(7, slot);
        } finally {
            payload.release();
        }
    }

    @Test
    void vanillaLoginPacketRoundtrip() {
        // 登录协议包（LOGIN CLIENTBOUND；登录监听器注入路径的输入格式）
        Packet<?> packet = new ClientboundLoginDisconnectPacket(Component.literal("relay-test"));
        ByteBuf payload = GatewayPacketCodec.encodeVanilla(
                packet, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.LOGIN, RegistryAccess.EMPTY);
        try {
            assertEquals(GatewayPacketCodec.KIND_VANILLA, GatewayPacketCodec.peekKind(payload));
            Packet<?> decoded = GatewayPacketCodec.decodeVanilla(
                    payload, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.LOGIN, RegistryAccess.EMPTY);
            // ClientboundLoginDisconnectPacket：1.21.5 及以前为普通类（getReason），1.21.6 起 record（reason）
#if MC_VER < MC_1_21_6
            assertEquals(Component.literal("relay-test"),
                    ((ClientboundLoginDisconnectPacket) decoded).getReason());
#else
            assertEquals(Component.literal("relay-test"),
                    ((ClientboundLoginDisconnectPacket) decoded).reason());
#endif
        } finally {
            payload.release();
        }
    }

#if MC_VER >= MC_1_21_1
    /**
     * 配置协议包（CONFIG CLIENTBOUND）往返（T10 CONFIG_S2C 帧格式）。
     * ClientboundFinishConfigurationPacket（configuration 包，跨段稳定）。
     */
    @Test
    void vanillaConfigPacketRoundtrip() {
        // 私有构造 + INSTANCE 单例
        Packet<?> packet = net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket.INSTANCE;
        ByteBuf payload = GatewayPacketCodec.encodeVanilla(
                packet, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.CONFIG, RegistryAccess.EMPTY);
        try {
            assertEquals(GatewayPacketCodec.KIND_VANILLA, GatewayPacketCodec.peekKind(payload));
            Packet<?> decoded = GatewayPacketCodec.decodeVanilla(
                    payload, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.CONFIG, RegistryAccess.EMPTY);
            assertInstanceOf(net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket.class,
                    decoded);
        } finally {
            payload.release();
        }
    }
#endif

    @Test
    void hassiumBusinessPacketDecode() {
        ChunkHashS2CPacket original = new ChunkHashS2CPacket("minecraft:overworld",
                List.of(new ChunkHashS2CPacket.Entry(1, 2, 0xDEADBEEFL, 3)));
        // 手工构造 kind=1 帧 payload（与主控侧 T11 发送格式一致）
        ByteBuf payload = Unpooled.buffer();
        try {
            payload.writeByte(GatewayPacketCodec.KIND_HASSIUM);
            payload.writeByte(GatewayPacketCodec.HassiumSub.CHUNK_HASH.id());
            original.encode(new FriendlyByteBuf(payload));

            assertEquals(GatewayPacketCodec.KIND_HASSIUM, GatewayPacketCodec.peekKind(payload));
            GatewayPacketCodec.HassiumPacket hp = GatewayPacketCodec.decodeHassium(payload);
            assertEquals(GatewayPacketCodec.HassiumSub.CHUNK_HASH, hp.sub());
            ChunkHashS2CPacket decoded = assertInstanceOf(ChunkHashS2CPacket.class, hp.packet());
            assertEquals(original, decoded);
        } finally {
            payload.release();
        }
    }

    @Test
    void unknownKindAndSubRejected() {
        ByteBuf badKind = Unpooled.buffer();
        try {
            badKind.writeByte(99);
            assertThrows(IllegalArgumentException.class, () -> GatewayPacketCodec.decodeVanilla(
                    badKind, PacketFlow.CLIENTBOUND, GatewayPacketCodec.GatewayProtocol.PLAY, RegistryAccess.EMPTY));
        } finally {
            badKind.release();
        }

        ByteBuf badSub = Unpooled.buffer();
        try {
            badSub.writeByte(GatewayPacketCodec.KIND_HASSIUM);
            badSub.writeByte(127);
            assertThrows(IllegalArgumentException.class, () -> GatewayPacketCodec.decodeHassium(badSub));
        } finally {
            badSub.release();
        }
    }

    @Test
    void peekKindDoesNotConsume() {
        ByteBuf payload = Unpooled.buffer();
        try {
            payload.writeByte(GatewayPacketCodec.KIND_HASSIUM);
            payload.writeByte(GatewayPacketCodec.HassiumSub.LIGHT_DELTA.id());
            int before = payload.readableBytes();
            assertEquals(GatewayPacketCodec.KIND_HASSIUM, GatewayPacketCodec.peekKind(payload));
            assertEquals(before, payload.readableBytes(), "peek 不得消费");
        } finally {
            payload.release();
        }
    }

    @Test
    void controlFrameVarintHelpersConsistentWithFriendlyByteBuf() {
        // 帧 payload 的 varint 读写与 FriendlyByteBuf 线格式一致（T4 帧协议约定）
        ByteBuf buf = Unpooled.buffer();
        try {
            FriendlyByteBuf fbuf = new FriendlyByteBuf(buf);
            fbuf.writeVarInt(0x12345678);
            fbuf.resetReaderIndex();
            assertEquals(0x12345678, ControlFrameCodec.readVarInt(buf));
        } finally {
            buf.release();
        }
    }
}
