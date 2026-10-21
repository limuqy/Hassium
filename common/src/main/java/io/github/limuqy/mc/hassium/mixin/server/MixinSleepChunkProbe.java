package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.world.level.chunk.LevelChunk.class)
public abstract class MixinSleepChunkProbe {
#if MC_VER < MC_1_21_2
    @Inject(method="setLoaded",at=@At("HEAD"))
    private void hassium$sleepUnloadEvidence(boolean loaded,CallbackInfo ci) {
        if(!loaded && io.github.limuqy.mc.hassium.server.sleep.SleepRuntimeProbe.ENABLED)
            io.github.limuqy.mc.hassium.server.sleep.SleepRuntimeProbe.unloaded((net.minecraft.world.level.chunk.LevelChunk)(Object)this);
    }
#endif
}
