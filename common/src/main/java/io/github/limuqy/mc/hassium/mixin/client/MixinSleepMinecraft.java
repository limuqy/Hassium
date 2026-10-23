package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.sleep.SleepClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.Minecraft.class)
public abstract class MixinSleepMinecraft {
    @Inject(method="pauseGame",at=@At("HEAD"))
    private void hassium$sleepPanel(boolean menu, CallbackInfo ci) {
        if(SleepClient.sleeping()) {
            SleepClient.wake();
        }
        else SleepClient.captureHeldInputs();
    }
    @Inject(method="tick",at=@At("HEAD"))
    private void hassium$sleepTick(CallbackInfo ci) { SleepClient.tick(); }
}
