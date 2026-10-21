package io.github.limuqy.mc.hassium.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.screens.ConnectScreen.class)
public abstract class MixinSleepConnectScreen {
#if MC_VER < MC_1_21_1
    @Inject(method="startConnecting",at=@At("HEAD"),cancellable=true)
    private static void hassium$confirmAfterSleep(net.minecraft.client.gui.screens.Screen parent,net.minecraft.client.Minecraft mc,
            net.minecraft.client.multiplayer.resolver.ServerAddress address,net.minecraft.client.multiplayer.ServerData target,boolean quick,CallbackInfo ci) {
#elif MC_VER < MC_1_21_2
    @Inject(method="startConnecting",at=@At("HEAD"),cancellable=true)
    private static void hassium$confirmAfterSleep(net.minecraft.client.gui.screens.Screen parent,net.minecraft.client.Minecraft mc,
            net.minecraft.client.multiplayer.resolver.ServerAddress address,net.minecraft.client.multiplayer.ServerData target,boolean quick,
            net.minecraft.client.multiplayer.TransferState transfer,CallbackInfo ci) {
#endif
#if MC_VER < MC_1_21_2
        if(!io.github.limuqy.mc.hassium.client.sleep.SleepClient.needsManualConfirmation(target)) return;
        mc.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(accepted->{
            if(accepted) { io.github.limuqy.mc.hassium.client.sleep.SleepClient.allowManualConnection(); io.github.limuqy.mc.hassium.compat.SleepClientCompat.connect(parent,target); }
            else mc.setScreen(parent);
        },net.minecraft.network.chat.Component.translatable("hassium.sleep.manual_title"),net.minecraft.network.chat.Component.translatable("hassium.sleep.manual_help")));
        ci.cancel();
    }
#endif
}
