package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER >= MC_1_21_1
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
#endif

/**
 * 预握手 C2S payload（配置阶段声明 Hassium 能力；1.20.2+ 配置阶段协议原生支持）。
 * <p>
 * 与 {@link PreHandshakeProtocol#encodeFields} 的 legacy 布局字段一致；
 * 服务端收到后仅 {@link PlayerCompressionTracker#markPreHandshake}，
 * 完整协商（ZSTD/聚合/数据面/位置）仍在 Play 阶段握手完成。
 * <p>
 * 仅 {@code MC_VER >= MC_1_21_1} 编译（1.20.1 无 common 包 CustomPacketPayload；
 * fabric 旧段走 legacy Identifier 通道）。
 */
#if MC_VER >= MC_1_21_1
public record PreHandshakePayload(
        int protocolVersion,
        String modVersion,
        boolean clientCache,
        boolean globalCompression,
        boolean compactHeader
) implements CustomPacketPayload {

    public static final Type<PreHandshakePayload> TYPE =
            new Type<>(ResourceLocationCompat.create(Constants.MOD_ID, "prehandshake_c2s"));

    public static final StreamCodec<FriendlyByteBuf, PreHandshakePayload> STREAM_CODEC =
            StreamCodec.of(PreHandshakePayload::encode, PreHandshakePayload::decode);

    public static PreHandshakePayload create() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        return new PreHandshakePayload(
                Constants.CURRENT_PROTOCOL_VERSION,
                Constants.MOD_VERSION,
                cfg.isClientCacheEnabled(),
                cfg.isGlobalPacketCompressionEnabled(),
                cfg.isCompactHeaderEnabled());
    }

    private static void encode(FriendlyByteBuf buf, PreHandshakePayload payload) {
        buf.writeVarInt(payload.protocolVersion);
        buf.writeUtf(payload.modVersion);
        buf.writeBoolean(payload.clientCache);
        buf.writeBoolean(payload.globalCompression);
        buf.writeBoolean(payload.compactHeader);
    }

    private static PreHandshakePayload decode(FriendlyByteBuf buf) {
        return new PreHandshakePayload(
                buf.readVarInt(),
                buf.readUtf(128),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
#else
class PreHandshakePayload { // 1.20.2-1.20.4 / 1.20.1：不使用 payload 形态（fabric legacy / login query）
}
#endif
