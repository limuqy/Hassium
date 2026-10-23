package io.github.limuqy.mc.hassium.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class MixinSleepGameRenderer {
#if MC_VER < MC_1_21_2
#if MC_VER < MC_1_21_1
    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;flush()V",shift=At.Shift.AFTER))
#else
    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;flush()V"))
#endif
    private void hassium$showSleepStatus(CallbackInfo ci) {
        io.github.limuqy.mc.hassium.compat.SleepClientCompat.renderStatus();
    }
#endif
}
