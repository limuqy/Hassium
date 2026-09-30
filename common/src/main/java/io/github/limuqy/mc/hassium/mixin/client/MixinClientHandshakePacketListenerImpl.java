package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ReflectionCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 登录期能力握手——客户端应答（1.20.1 login query 载体；全版本共用目标类
 * {@code ClientHandshakePacketListenerImpl}，handleCustomQuery 全段存在）。
 * <p>
 * 仅消费 {@code hassium:login_hello} 通道：以客户端声明位应答（1.20.2+ 原版 codec
 * 会丢弃未知应答体，故该通道仅 1.20.1 有意义，1.20.2+ 由配置阶段 PreHandshakePayload
 * 声明）。网络开关关闭或非本模组通道：原样放行（vanilla 客户端自动回空应答 →
 * 服务端走原版路径）。
 */
@Mixin(net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl.class)
public class MixinClientHandshakePacketListenerImpl {

    @Inject(method = "handleCustomQuery(Lnet/minecraft/network/protocol/login/ClientboundCustomQueryPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$onLoginCustomQuery(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        // 1.20.2+ 原版 codec 丢弃 query 体（DiscardedQueryPayload），登录 query 载体失效；
        // 能力声明走配置阶段（服务端配置任务下发 hello → 客户端应答 PreHandshakePayload）。
        // 原样放行。
    }

    /**
     * 1.20.2+ 登录 query 载体失效（见上），此类在 1.20.2+ 为空壳（mixins.json 注册位保留）。
     */
}

