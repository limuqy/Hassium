package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.compat.ShadowTickOwner;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shadow backend does not run block/fluid ticks. 1.21.2+ LevelTicks.schedule
 * calls Util.logAndPauseIfInIde when the neighbor column is not in allContainers,
 * flooding dev logs with schedule-tick ERROR and failing smoke LogAudit.
 * Only the LevelTicks bound to a ShadowSeedServer suppress scheduling.
 */
@Mixin(LevelTicks.class)
public class MixinLevelTicks implements ShadowTickOwner {
    @Unique private boolean hassium$shadowOwner;

    @Override public void hassium$setShadowOwner(boolean shadow) {
        hassium$shadowOwner = shadow;
    }


    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void hassium$skipScheduleOnShadow(ScheduledTick<?> tick, CallbackInfo ci) {
        if (hassium$shadowOwner) {
            ci.cancel();
        }
    }
}
