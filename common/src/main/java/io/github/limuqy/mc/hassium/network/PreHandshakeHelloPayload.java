package io.github.limuqy.mc.hassium.network;

#if MC_VER >= MC_1_21_1
import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
#endif

/**
 * 预握手 hello（S2C，配置阶段）——服务端主导协商的触发载体（1.21.1+）。
 * <p>
 * 1.21.1+ 能力协商由服务端发起：服务端在配置阶段经 loader 官方任务机制
 * （NeoForge {@code RegisterConfigurationTasksEvent} / Forge {@code GatherLoginConfigurationTasksEvent}）
 * 下发本 payload；客户端在 handler 内同步应答 {@link PreHandshakePayload}（C2S）。
 * 相比早期「客户端首个配置 tick 主动声明」方案，服务端任务只在通道协商完成后执行，
 * 消除了「发早被踢」（NeoForge No Payload Setup）与「配置期取连接」的客户端时机竞态。
 * <p>
 * 载荷为空（hello 仅作触发信号，能力位在客户端应答中携带）；1.20.1 无配置阶段，
 * 走 vanilla login query（见 {@code MixinServerLoginPacketListenerImpl}）。
 */
#if MC_VER >= MC_1_21_1
public record PreHandshakeHelloPayload() implements CustomPacketPayload {

    public static final PreHandshakeHelloPayload INSTANCE = new PreHandshakeHelloPayload();

    public static final Type<PreHandshakeHelloPayload> TYPE =
            new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.PRE_HANDSHAKE_HELLO_S2C));

    public static final StreamCodec<FriendlyByteBuf, PreHandshakeHelloPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
                // 空载荷：hello 仅作触发信号
            }, buf -> INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
#else
class PreHandshakeHelloPayload { // 1.20.1：无配置阶段（login query 载体）
}
#endif
