package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;

/**
 * CustomPayload 协议版本兼容层
 * <p>
 * 1.20.1（<1.21.1）: game 包，getIdentifier()/getData()，构造器 (ResourceLocation, FriendlyByteBuf)
 * 1.21.1+: common 包，payload().type().id()，CustomPacketPayload 有 type()，无 write()
 */
public final class PacketPayloadCompat {
    private PacketPayloadCompat() {}

    public static PacketId getPayloadId(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp) {
            return ResourceLocationCompat.toPacketId(cp.payload().type().id());
        }
        if (packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket cp) {
            return ResourceLocationCompat.toPacketId(cp.payload().type().id());
        }
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
            Identifier
            id, byte[] data) {
        return new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new RawCustomPayload(id, data));
    }

    public static byte[] extractPayloadData(Packet<?> packet) {
        // 1.21.1+: RawCustomPayload / Fabric RawPayload.data() / StreamCodec 编码剥头
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp) {
            if (cp.payload() instanceof RawCustomPayload raw) {
                return raw.data();
            }
            return PacketCodecCompat.extractCustomPayloadBytes(cp.payload(), null);
        }
        return null;
    }

    public static Packet<?> createClientboundPayload(
            Identifier
            id, byte[] data) {
        return new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new RawCustomPayload(id, data));
    }

    public static boolean isCustomPayloadPacket(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
            || packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
    }

    /**
     * 1.21.1+：非 {@code minecraft} 命名空间的 S2C custom payload（mod / 加载器注册类型，
     * 如 {@code neoforge:custom_time_packet}、{@code create:*}）。
     * <p>
     * 这类 payload 的客户端 handler 按注册时的具体类型对象分发；聚合重放只能构造
     * {@link RawCustomPayload}（id + 字节），进分发器即 ClassCastException
     * （NeoForge custom_time_packet 实证）。1.20.1 的分发按 id + 字节进行，无此问题，
     * 因此仅 1.21.1+ 段存在本判定，聚合入口据此对这类包直发。
     */
    public static boolean isModdedS2CCustomPayload(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket cp
                && !"minecraft".equals(cp.payload().type().id().getNamespace());
    }
    /**
     * 1.21.1+：为 payload id 创建类型化 {@code CustomPacketPayload.Type}。
     * fabric 端经 {@code PayloadTypeRegistry.playS2C().register(type, codec)} 注册后，
     * {@link #createClientboundPayload} 产出的 RawCustomPayload 才能被
     * {@code ClientboundCustomPayloadPacket} 正常编码——未注册类型编码回退 DiscardedPayload codec
     * （checkcast → ClassCastException，T5f 冒烟根因）。
     */
    public static net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<RawCustomPayload> payloadType(
Identifier
 id) {
        return new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(id);
    }

    /**
     * 1.21.1+：RawCustomPayload 字节流 codec（encode 直写字节；decode 读尽剩余——payload 为
     * CustomPayload 包末字段）。与 {@link #createClientboundPayload} 产出的实例类一致注册，
     * 否则注册 codec 的 T 参数与实例类不符仍会 checkcast CCE。
     */
    public static net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, RawCustomPayload> rawPayloadCodec(
Identifier
 id) {
        return net.minecraft.network.codec.StreamCodec.of(
                (buf, payload) -> buf.writeBytes(payload.data()),
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new RawCustomPayload(id, data);
                });
    }

    /**
     * 原始 CustomPacketPayload 实现，用于包装未注册的 payload 数据
     */
    public record RawCustomPayload(
Identifier
 id, byte[] data)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        @Override
        public net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<RawCustomPayload> type() {
            return new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(id);
        }
    }
}
