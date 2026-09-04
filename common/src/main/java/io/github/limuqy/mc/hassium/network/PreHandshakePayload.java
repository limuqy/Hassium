package io.github.limuqy.mc.hassium.network;

#if MC_VER >= MC_1_21_1
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.network.handshake.LoginCaps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
#endif

/**
 * 预握手 C2S payload（配置阶段声明 Hassium 能力；1.20.2+ 配置阶段协议原生支持）。
 * <p>
 * 直连拓扑下的 1.20.2+ 能力协商载体：字段带客户端声明位（{@link LoginCaps} 语义），
 * 服务端按位与后 {@link LoginHandshakeManager#markNegotiated}（认证已完成，
 * {@code getOwner()} 可取 UUID）；Play 期 {@code play_init_s2c} 下发协商结果。
 * <p>
 * 仅 {@code MC_VER >= MC_1_21_1} 编译（1.20.1 无 common 包 CustomPacketPayload；
 * 1.20.1 走 vanilla login query，见 {@code MixinServerLoginPacketListenerImpl}）。
 */
#if MC_VER >= MC_1_21_1
public record PreHandshakePayload(
        int protocolVersion,
        String modVersion,
        int clientCaps
) implements CustomPacketPayload {

    public static final Type<PreHandshakePayload> TYPE =
            new Type<>(ResourceLocationCompat.create(Constants.MOD_ID, "prehandshake_c2s"));

    public static final StreamCodec<FriendlyByteBuf, PreHandshakePayload> STREAM_CODEC =
            StreamCodec.of(PreHandshakePayload::encode, PreHandshakePayload::decode);

    public static PreHandshakePayload create() {
        return new PreHandshakePayload(
                Constants.CURRENT_PROTOCOL_VERSION,
                Constants.MOD_VERSION,
                LoginCaps.buildClientCaps());
    }

    private static void encode(FriendlyByteBuf buf, PreHandshakePayload payload) {
        buf.writeVarInt(payload.protocolVersion);
        buf.writeUtf(payload.modVersion, 128);
        buf.writeVarInt(payload.clientCaps);
    }

    private static PreHandshakePayload decode(FriendlyByteBuf buf) {
        return new PreHandshakePayload(
                buf.readVarInt(),
                buf.readUtf(128),
                buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
#else
class PreHandshakePayload { // 1.20.1：不使用 payload 形态（login query 载体）
}
#endif
