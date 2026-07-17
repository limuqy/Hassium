package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

/**
 * CustomPayload 协议版本兼容层
 * <p>
 * 1.20.1: game 包，getIdentifier()/getData()，构造器 (ResourceLocation, FriendlyByteBuf)
 * 1.20.2~1.20.4: common 包，payload().id()/payload().write(buf)，CustomPacketPayload 有 id()+write()
 * 1.20.5+: common 包，payload().type().id()，CustomPacketPayload 有 type()，无 write()
 */
public final class PacketPayloadCompat {
    private PacketPayloadCompat() {}

    public static
#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    getPayloadId(Packet<?> packet) {
#if MC_VER < MC_1_20_2
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket cp) {
            return cp.getIdentifier();
        }
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket cp) {
            return cp.getIdentifier();
        }
#else
#if MC_VER < MC_1_20_5
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp) {
            return cp.payload().id();
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket cp) {
            return cp.payload().id();
        }
#else
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp) {
            return cp.payload().type().id();
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket cp) {
            return cp.payload().type().id();
        }
#endif
#endif
        return null;
    }

    public static byte[] extractPayloadData(Packet<?> packet) {
#if MC_VER < MC_1_20_2
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket cp) {
            FriendlyByteBuf payloadBuf = cp.getData();
            byte[] data = new byte[payloadBuf.readableBytes()];
            payloadBuf.readBytes(data);
            return data;
        }
#else
#if MC_VER < MC_1_20_5
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp) {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            cp.payload().write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            return data;
        }
#else
        // 1.20.5+: RawCustomPayload / Fabric RawPayload.data() / StreamCodec 编码剥头
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp) {
            if (cp.payload() instanceof RawCustomPayload raw) {
                return raw.data();
            }
            return PacketCodecCompat.extractCustomPayloadBytes(cp.payload(), null);
        }
#endif
#endif
        return null;
    }

    public static Packet<?> createClientboundPayload(
#if MC_VER < MC_1_21_11
            ResourceLocation
#else
            Identifier
#endif
            id, byte[] data) {
#if MC_VER < MC_1_20_2
        return new net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket(
                id, new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
#else
        return new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new RawCustomPayload(id, data));
#endif
    }

    public static boolean isCustomPayloadPacket(Packet<?> packet) {
#if MC_VER < MC_1_20_2
        return packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
            || packet instanceof net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
#else
        return packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
            || packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
#endif
    }

#if MC_VER >= MC_1_20_2
    /**
     * 原始 CustomPacketPayload 实现，用于包装未注册的 payload 数据
     */
#if MC_VER < MC_1_20_5
    private record RawCustomPayload(
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
 id, byte[] data)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeBytes(data);
        }
        @Override
        public
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
 id() {
            return id;
        }
    }
#else
    private record RawCustomPayload(
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
 id, byte[] data)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        @Override
        public net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<RawCustomPayload> type() {
            return new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(id);
        }
    }
#endif
#endif
}
