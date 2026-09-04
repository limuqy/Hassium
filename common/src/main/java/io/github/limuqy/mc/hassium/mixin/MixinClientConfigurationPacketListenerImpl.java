package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ReflectionCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.handshake.LoginCaps;
#if MC_VER >= MC_1_21_1
import io.github.limuqy.mc.hassium.network.PreHandshakePayload;
#endif
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 登录期能力握手——客户端 1.20.2+ 配置阶段声明（C2S {@code PreHandshakePayload}）。
 * <p>
 * 1.20.2+ 原版 codec 对未知 login query 应答体逐字节丢弃，登录期无法携带能力体；
 * 能力声明改走配置阶段自定义 payload（loader 注册真实通道，两端可读）。本 mixin 在
 * 配置监听器首个 tick 一次性发送声明（vanilla 通道层直接 send；payload 已在
 * forge/neoforge 注册 configurationToServer、fabric 由 API 通道层处理）。
 * <p>
 * 1.20.1 无此类（mixin 目标缺失自动跳过），由 {@code MixinClientHandshakePacketListenerImpl}
 * 的 login query 应答承担。网络开关关闭时不发送（服务端走原版路径）。
 */
#if MC_VER >= MC_1_21_1
@Mixin(net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl.class)
public class MixinClientConfigurationPacketListenerImpl {

    @Unique
    private boolean hassium$announced;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void hassium$announcePreHandshake(CallbackInfo ci) {
        if (this.hassium$announced) {
            return;
        }
        this.hassium$announced = true;
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            return;
        }
        Connection connection = (Connection) ReflectionCompat.getFieldByTypeOrNull(this, Connection.class, true);
        if (connection == null || !connection.isConnected()) {
            return;
        }
        try {
            // 载体发送经 loader SPI：配置期 Minecraft.getConnection() 恒为 null（play listener
            // 未创建），必须传本配置监听器的 connection——forge/neoforge 经 vanilla
            // ServerboundCustomPayloadPacket 直发（loader 按 CONFIGURATION 协议分派 codec）；
            // fabric 走 ClientConfigurationConnectionEvents.START API，本 SPI 为 no-op。
            io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.announcePreHandshake(connection);
            Constants.LOG.info("Hassium: pre-handshake announced (config phase, clientCaps=0x{})",
                    Integer.toHexString(LoginCaps.buildClientCaps()));
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Failed to send pre-handshake announcement", e);
        }
    }
}
#else
/**
 * 1.20.1 无配置阶段（且 {@code PreHandshakePayload} 为 1.21.1+ 形态）；
 * 占位空壳保留 mixins.json 注册位。login query 应答见
 * {@code MixinClientHandshakePacketListenerImpl}。
 */
@Mixin(net.minecraft.client.Minecraft.class)
public class MixinClientConfigurationPacketListenerImpl {
}
#endif



