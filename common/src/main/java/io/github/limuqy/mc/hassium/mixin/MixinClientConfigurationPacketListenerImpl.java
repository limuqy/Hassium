package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * <p>
 * 注入点必须是 {@code ClientHandshakePacketListenerImpl.handleGameProfile} TAIL
 * 而非 {@code ClientConfigurationPacketListenerImpl.<init>}：vanilla 1.20.5+ 的
 * handleGameProfile 先构造配置监听器、再 {@code setupInboundProtocol}，最后才
 * {@code setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND)}。在 {@code <init>}
 * TAIL 发送时出站协议仍是 LOGIN，forge 52 客户端会抛
 * {@code Sending unknown packet 'serverbound/minecraft:custom_payload'}（1.21.1 实测）。
 */
#if MC_VER >= MC_1_20_2
@Mixin(net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl.class)
#else
@Mixin(net.minecraft.server.MinecraftServer.class)
#endif
public abstract class MixinClientConfigurationPacketListenerImpl {

#if MC_VER >= MC_1_20_5
    @Shadow
    private net.minecraft.network.Connection connection;

    /**
     * 登录完成（进入配置阶段）即发预握手，早于 {@code ServerPlayer} 创建。
     * 此时出站协议已切到 CONFIGURATION，custom_payload 可编码。
     * <p>
     * 仅 forge 走本路径（{@link io.github.limuqy.mc.hassium.platform.services.INetworkManagerService#sendPreHandshake}
     * 经 {@code CHANNEL.send} 以 ForgePayload 包装发送，绕开 forge 配置阶段
     * custom_payload 由 ForgePayload 统一接管的 cast 问题）；fabric 由
     * C2SConfigurationChannelEvents.REGISTER 负责发送；neoforge 服务端只收不发。
     * <p>
     * 注入目标分段：1.21.1 及以前 = {@code handleGameProfile(ClientboundGameProfilePacket)}；
     * 1.21.2+ = {@code handleLoginFinished(ClientboundLoginFinishedPacket)}（登录包改名，
     * 1.21.11 fabric 实测 mixin 找不到 handleGameProfile）。
     */
#if MC_VER < MC_1_21_2
    @Inject(method = "handleGameProfile", at = @At("TAIL"))
#else
    @Inject(method = "handleLoginFinished", at = @At("TAIL"))
#endif
    private void hassium$sendPreHandshake(CallbackInfo ci) {
        hassium$doSendPreHandshake();
    }

    @org.spongepowered.asm.mixin.Unique
    private void hassium$doSendPreHandshake() {
        if (!"Forge".equals(Services.PLATFORM.getPlatformName())) {
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
            Services.NETWORK_MANAGER.sendPreHandshake(this.connection);
        } catch (Exception e) {
            DebugLogger.warn(LogType.NETWORK, "[PRE_HANDSHAKE] Failed to send pre-handshake: {}", e.toString());
        }
    }
#elif MC_VER >= MC_1_20_2
    // 1.20.2-1.20.4：neoforge/forge 无配置阶段通道（fabric 走 legacy API），无预握手。
#endif
}
