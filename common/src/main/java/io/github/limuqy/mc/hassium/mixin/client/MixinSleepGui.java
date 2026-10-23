package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.compat.SleepClientCompat;
#if MC_VER >= MC_1_21_2
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw into the native GUI pass, whose graphics construction changed after 1.21.5. */
@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class MixinSleepGui {
#if MC_VER >= MC_1_21_2
    @Inject(method="render",at=@At("TAIL"))
    private void hassium$showSleepStatus(GuiGraphics graphics,DeltaTracker delta,CallbackInfo ci) {
        SleepClientCompat.renderStatus(graphics);
    }
#endif
}
