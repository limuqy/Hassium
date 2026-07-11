package io.github.limuqy.mc.hassium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.2+：{@code onDisconnect} 从 {@code ClientPacketListener} 上移到
 * {@code ClientCommonPacketListenerImpl}，在此注入断开清理。
 * <p>
 * 1.20.1 无 {@code ClientCommonPacketListenerImpl}，挂空壳到 {@code Minecraft}
 * 以满足 mixins.json 注册。
 */
#if MC_VER >= MC_1_20_2
@Mixin(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class)
#else
@Mixin(net.minecraft.client.Minecraft.class)
#endif
public class MixinClientCommonPacketListenerImpl {

#if MC_VER >= MC_1_20_2
    /**
     * 断开连接时清理（1.20.2+）
     * <p>
     * 1.20.2~1.20.6：{@code onDisconnect(Component)}
     * 1.21.1+：{@code onDisconnect(DisconnectionDetails)}
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hassium$onDisconnect(
#if MC_VER < MC_1_21_1
            net.minecraft.network.chat.Component reason,
#else
            net.minecraft.network.DisconnectionDetails details,
#endif
            CallbackInfo ci) {
        MixinClientPacketListener.hassium$cleanupOnDisconnect();
    }
#endif
}
