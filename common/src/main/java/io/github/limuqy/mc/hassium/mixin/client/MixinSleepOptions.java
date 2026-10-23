package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.compat.SleepChunkResidency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runtime view override; never changes saved preferences or broadcasts a smaller server view. */
@Mixin(net.minecraft.client.Options.class)
public abstract class MixinSleepOptions {
    @Inject(method="getEffectiveRenderDistance",at=@At("RETURN"),cancellable=true)
    private void hassium$sleepView(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(SleepChunkResidency.effectiveRenderDistance(cir.getReturnValue()));
    }
}
