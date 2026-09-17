package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.ClientLifecycleHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 点连接后立即装配影子端 WorldLoader，与 TCP/登录/握手重叠。
 * <p>
 * 注入 {@code startConnecting} TAIL：此时 {@code clearLevel}/{@code disconnect}
 * 已跑完，不会在旧会话拆除前抢跑；执行器与 getOrCreate 在后台，不挡连服线程。
 * 不得 unpark（仍由 {@code onLogin} 放行）。
 */
@Mixin(net.minecraft.client.gui.screens.ConnectScreen.class)
public abstract class MixinConnectScreen {

#if MC_VER < MC_1_21_1
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;Z)V",
            at = @At("TAIL"))
    private static void hassium$onStartConnecting(Screen parent, Minecraft minecraft, ServerAddress address,
                                                 ServerData serverData, boolean quickPlay, CallbackInfo ci) {
        ClientLifecycleHelper.onStartConnecting(serverData);
    }
#else
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V",
            at = @At("TAIL"))
    private static void hassium$onStartConnecting(Screen parent, Minecraft minecraft, ServerAddress address,
                                                 ServerData serverData, boolean quickPlay,
                                                 net.minecraft.client.multiplayer.TransferState transferState,
                                                 CallbackInfo ci) {
        ClientLifecycleHelper.onStartConnecting(serverData);
    }
#endif
}
