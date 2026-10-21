package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.server.level.ServerEntity.class)
public interface SleepServerEntityAccess {
#if MC_VER < MC_1_21_2
    @Accessor("positionCodec") net.minecraft.network.protocol.game.VecDeltaCodec hassium$sleepPositionCodec();
#endif
}
