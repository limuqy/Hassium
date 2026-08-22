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
 * 1.20.1（<1.21.1）: game 包，getIdentifier()/getData()，构造器 (ResourceLocation, FriendlyByteBuf)
 * 1.21.1+: common 包，payload().type().id()，CustomPacketPayload 有 type()，无 write()
 */
public final class PacketPayloadCompat {
    private PacketPayloadCompat() {}

    public static PacketId getPayloadId(Packet<?> packet) {
#if MC_VER < MC_1_21_1
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket cp) {
            return ResourceLocationCompat.toPacketId(cp.getIdentifier());
        }
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket cp) {
            return ResourceLocationCompat.toPacketId(cp.getIdentifier());
        }
#else
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp) {
            return ResourceLocationCompat.toPacketId(cp.payload().type().id());
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket cp) {
            return ResourceLocationCompat.toPacketId(cp.payload().type().id());
        }
#endif
        return null;
    }

    /** {@link PacketId} 版本：业务侧直传稳定值类型，内部经 compat 转 vanilla。 */
    public static Packet<?> createClientboundPayload(PacketId id, byte[] data) {
        return createClientboundPayloadVanilla(ResourceLocationCompat.vanilla(id), data);
    }

    /**
     * vanilla 类型版本（compat / 加载器边界）。聚合路径已改 {@link PacketId}，
     * 业务代码请用 {@link #createClientboundPayload(PacketId, byte[])}。
     */
    public static Packet<?> createClientboundPayloadVanilla(
#if MC_VER < MC_1_21_11
            ResourceLocation
#else
            Identifier
#endif
            id, byte[] data) {
#if MC_VER < MC_1_21_1
        return new net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket(
                id, new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
#else
        return new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new RawCustomPayload(id, data));
#endif
    }

    public static byte[] extractPayloadData(Packet<?> packet) {
#if MC_VER < MC_1_21_1
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket cp) {
            FriendlyByteBuf payloadBuf = cp.getData();
            byte[] data = new byte[payloadBuf.readableBytes()];
            payloadBuf.readBytes(data);
            return data;
        }
#else
        // 1.21.1+: RawCustomPayload / Fabric RawPayload.data() / StreamCodec 编码剥头
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp) {
            if (cp.payload() instanceof RawCustomPayload raw) {
                return raw.data();
            }
            return PacketCodecCompat.extractCustomPayloadBytes(cp.payload(), null);
        }
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
#if MC_VER < MC_1_21_1
        return new net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket(
                id, new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data)));
#else
        return new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new RawCustomPayload(id, data));
#endif
    }

    public static boolean isCustomPayloadPacket(Packet<?> packet) {
#if MC_VER < MC_1_21_1
        return packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
            || packet instanceof net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
#else
        return packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
            || packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
#endif
    }
#if MC_VER >= MC_1_21_1
    /**
     * 1.21.1+：为 payload id 创建类型化 {@code CustomPacketPayload.Type}。
     * fabric 端经 {@code PayloadTypeRegistry.playS2C().register(type, codec)} 注册后，
     * {@link #createClientboundPayload} 产出的 RawCustomPayload 才能被
     * {@code ClientboundCustomPayloadPacket} 正常编码——未注册类型编码回退 DiscardedPayload codec
     * （checkcast → ClassCastException，T5f 冒烟根因）。
     */
    public static net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<RawCustomPayload> payloadType(
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
 id) {
        return new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(id);
    }

    /**
     * 1.21.1+：RawCustomPayload 字节流 codec（encode 直写字节；decode 读尽剩余——payload 为
     * CustomPayload 包末字段）。与 {@link #createClientboundPayload} 产出的实例类一致注册，
     * 否则注册 codec 的 T 参数与实例类不符仍会 checkcast CCE。
     */
    public static net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, RawCustomPayload> rawPayloadCodec(
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
 id) {
        return net.minecraft.network.codec.StreamCodec.of(
                (buf, payload) -> buf.writeBytes(payload.data()),
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new RawCustomPayload(id, data);
                });
    }
#endif

#if MC_VER >= MC_1_21_1
    /**
     * 原始 CustomPacketPayload 实现，用于包装未注册的 payload 数据
     */
    public record RawCustomPayload(
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
}
