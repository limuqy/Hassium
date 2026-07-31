package io.github.limuqy.mc.hassium.mixin;

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
    private static void hassium$prepareInitialConnection(Screen screen, Minecraft minecraft,
                                                           ServerAddress address, ServerData serverData,
#else
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/transfer/TransferState;)V", at = @At("HEAD"))
    private static void hassium$prepareInitialConnection(Screen screen, Minecraft minecraft,
                                                           ServerAddress address, ServerData serverData,
#endif
                                                           boolean quickPlay, Object transferState, CallbackInfo ci) {
        hassium$capture(serverData);
    }
#endif

    private static void hassium$capture(ServerData serverData) {
        if (serverData == null || ClientFailoverAttemptMarker.isMarked()
                || serverData.name.startsWith("hassium-failover:")) {
            return;
        }
        ClientFailoverIdentity.prepareInitialConnection(serverData.ip);
    }
}
