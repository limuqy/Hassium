package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.sleep.SleepClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.Minecraft.class)
public abstract class MixinSleepMinecraft {
#if MC_VER < MC_1_21_2
    @Inject(method="pauseGame",at=@At("HEAD"),cancellable=true)
    private void hassium$sleepPanel(boolean menu, CallbackInfo ci) {
        if(SleepClient.sleeping()) {
            if(SleepClient.inPlaceSupported()) SleepClient.wake();
            else { SleepClient.showPanel(); ci.cancel(); }
        }
        else SleepClient.captureHeldInputs();
    }
    @Inject(method="handleKeybinds",at=@At("HEAD"),cancellable=true)
    private void hassium$sleepInputBarrier(CallbackInfo ci) { if(SleepClient.blocksInput()) ci.cancel(); }
    @Inject(method="tick",at=@At("HEAD"))
    private void hassium$sleepTick(CallbackInfo ci) { SleepClient.tick(); }
#endif
}
