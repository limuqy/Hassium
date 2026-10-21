package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
@org.spongepowered.asm.mixin.Pseudo
public abstract class MixinSleepBlockEntityProbe {
#if MC_VER < MC_1_21_2
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.block.entity.BlockEntity blockEntity;
    @Inject(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",shift=At.Shift.AFTER))
    private void hassium$sleepTickerEvidence(CallbackInfo ci) {
        if(io.github.limuqy.mc.hassium.server.sleep.SleepRuntimeProbe.ENABLED)
            io.github.limuqy.mc.hassium.server.sleep.SleepRuntimeProbe.hopperTick(blockEntity);
    }
#endif
}
