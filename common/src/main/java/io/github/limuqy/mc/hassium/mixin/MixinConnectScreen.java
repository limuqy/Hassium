package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverAttemptMarker;
import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class MixinConnectScreen {
#if MC_VER < MC_1_20_5
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;Z)V", at = @At("HEAD"))
    private static void hassium$prepareInitialConnection(Screen screen, Minecraft minecraft,
                                                           ServerAddress address, ServerData serverData,
                                                           boolean quickPlay, CallbackInfo ci) {
        hassium$capture(serverData);
    }
#else
#if MC_VER < MC_1_21_6
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V", at = @At("HEAD"))
    // mixin 的 @Inject handler 必须与目标方法描述符精确匹配，不能用 Object 占位
    // （否则 1.20.5~1.21.5 启动即 InvalidInjectionException）。类型不同仅存于未编译分支。
    private static void hassium$prepareInitialConnection(Screen screen, Minecraft minecraft,
                                                           ServerAddress address, ServerData serverData,
                                                           boolean quickPlay,
                                                           net.minecraft.client.multiplayer.TransferState transferState,
                                                           CallbackInfo ci) {
        hassium$capture(serverData);
    }
#else
    // 1.21.6~1.21.11 的 TransferState 仍在 net.minecraft.client.multiplayer 包
    // （mojmap 1.21.6/1.21.7/1.21.8/1.21.10/1.21.11 验证；无 transfer 子包）。
    // 签名与 <1.21.6 完全相同，保留分支仅为未来迁移预留。
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V", at = @At("HEAD"))
    private static void hassium$prepareInitialConnection(Screen screen, Minecraft minecraft,
                                                           ServerAddress address, ServerData serverData,
                                                           boolean quickPlay,
                                                           net.minecraft.client.multiplayer.TransferState transferState,
                                                           CallbackInfo ci) {
        hassium$capture(serverData);
    }
#endif
#endif

    private static void hassium$capture(ServerData serverData) {
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()) {
            io.github.limuqy.mc.hassium.Constants.LOG.info("[diag] ConnectScreen.capture serverData={} marked={} namePrefix={}",
                    serverData == null ? "null" : serverData.ip,
                    ClientFailoverAttemptMarker.isMarked(),
                    serverData == null ? "-" : serverData.name.startsWith("hassium-failover:"));
        }
        if (serverData == null || ClientFailoverAttemptMarker.isMarked()
                || serverData.name.startsWith("hassium-failover:")) {
            return;
        }
        ClientFailoverIdentity.prepareInitialConnection(serverData.ip);
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()) {
            io.github.limuqy.mc.hassium.Constants.LOG.info("[diag] ConnectScreen.capture after prepare marked={}",
                    ClientFailoverAttemptMarker.isMarked());
        }
    }
}
