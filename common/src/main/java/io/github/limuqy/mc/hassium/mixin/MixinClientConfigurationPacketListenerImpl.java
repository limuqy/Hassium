package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.2+ 客户端配置阶段发送预握手（neoforge/forge 路径）。
 * <p>
 * fabric 不走本 mixin：配置阶段 C2S 受 fabric 通道声明机制约束
 * （{@code C2SConfigurationChannelEvents.REGISTER} 后才可发），由
 * {@code FabricNetworkManager} 用 fabric API 在 REGISTER 事件中发送。
 * <p>
 * 1.20.1 无配置阶段，挂空壳到 {@code MinecraftServer} 以满足 mixins.json
 * 注册（同 {@link MixinPlayerChunkSender} 模式）。
 */
#if MC_VER >= MC_1_20_2
@Mixin(net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl.class)
#else
@Mixin(net.minecraft.server.MinecraftServer.class)
#endif
public abstract class MixinClientConfigurationPacketListenerImpl {

#if MC_VER >= MC_1_20_5
    /**
     * 配置监听器创建（进入配置阶段）即发预握手，早于 {@code ServerPlayer} 创建。
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void hassium$sendPreHandshake(CallbackInfo ci) {
        // fabric：由 fabric API 的 C2SConfigurationChannelEvents.REGISTER 负责发送
        if ("Fabric".equals(Services.PLATFORM.getPlatformName())) {
            return;
        }
        // 单人/局域网房主：integrated server 本地进程，握手无收益（同 Play 握手逻辑）
        if (net.minecraft.client.Minecraft.getInstance().getSingleplayerServer() != null) {
            return;
        }
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                .isNetworkCompressionEnabled()) {
            return;
        }
        try {
            net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl self =
                    (net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl) (Object) this;
            self.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    io.github.limuqy.mc.hassium.network.PreHandshakePayload.create()));
            DebugLogger.info(LogType.NETWORK, "[PRE_HANDSHAKE] Sent pre-handshake (configuration phase)");
        } catch (Exception e) {
            DebugLogger.warn(LogType.NETWORK, "[PRE_HANDSHAKE] Failed to send pre-handshake: {}", e.toString());
        }
    }
#elif MC_VER >= MC_1_20_2
    // 1.20.2-1.20.4：neoforge/forge 无配置阶段通道（fabric 走 legacy API），无预握手。
#endif
}
