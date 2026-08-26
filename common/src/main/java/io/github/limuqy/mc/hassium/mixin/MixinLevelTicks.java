package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
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
 * Dedicated-server processes skip this mixin via RuntimeServerContext.
 */
@Mixin(LevelTicks.class)
public class MixinLevelTicks {

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void hassium$skipScheduleOnShadow(ScheduledTick<?> tick, CallbackInfo ci) {
        if (RuntimeServerContext.isShadowServerContext()) {
            ci.cancel();
        }
    }
}
