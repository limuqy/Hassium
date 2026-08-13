package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.client.ConnectScreenAccessor;
import io.github.limuqy.mc.hassium.network.core.NetworkCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#if MC_VER >= MC_1_20_5
import net.minecraft.client.multiplayer.TransferState;
#endif

/**
 * M3 主连接失效恢复（仅网关登录）：{@code startConnecting} HEAD 捕获连接意图
 * （ServerData + 父屏），供 {@link NetworkCore#tryStartGatewayOnlyLogin} 在连接失败
 * （DisconnectedScreen 拦截）时做 store 命中决策；同时实现 {@link ConnectScreenAccessor}
 * （置空原版连接 / 更新状态文案）。用户取消通知在 {@code MixinMinecraft.setScreen} HEAD
 * （ConnectScreen 无 onClose 覆写，所有版本走 Cancel 按钮 → setScreen(parent)）。
 *
 * <p>纯客户端 mixin（client 列表；dedicated server 不加载）。
 */
@Mixin(ConnectScreen.class)
public abstract class MixinConnectScreen implements ConnectScreenAccessor {

    @Accessor("connection")
    public abstract void hassium$setConnection(Connection connection);

    @Accessor("status")
    public abstract void hassium$setStatus(Component status);

    /** 取消（Cancel 按钮/ESC）由 {@code MixinMinecraft.setScreen} 拦截通知
     *  （ConnectScreen 无 onClose 覆写：所有版本均走 init() 的 Cancel 按钮 → setScreen(parent)）。 */

#if MC_VER < MC_1_20_5
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;Z)V", at = @At("HEAD"))
    private static void hassium$captureConnectIntent(Screen parent, Minecraft minecraft,
                                                     ServerAddress address, ServerData serverData,
                                                     boolean quickPlay, CallbackInfo ci) {
        NetworkCore.getInstance().captureConnectIntent(serverData, parent);
    }
#else
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V", at = @At("HEAD"))
    private static void hassium$captureConnectIntent(Screen parent, Minecraft minecraft,
                                                     ServerAddress address, ServerData serverData,
                                                     boolean quickPlay, TransferState transferState, CallbackInfo ci) {
        NetworkCore.getInstance().captureConnectIntent(serverData, parent);
    }
#endif
}
