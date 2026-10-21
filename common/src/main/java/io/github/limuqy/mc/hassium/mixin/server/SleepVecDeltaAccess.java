package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets="net.minecraft.network.protocol.game.VecDeltaCodec")
public interface SleepVecDeltaAccess {
#if MC_VER < MC_1_21_2
    @Accessor("base") net.minecraft.world.phys.Vec3 hassium$sleepBase();
#endif
}
