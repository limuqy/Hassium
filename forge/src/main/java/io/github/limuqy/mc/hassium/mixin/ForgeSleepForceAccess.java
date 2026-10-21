package io.github.limuqy.mc.hassium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.server.level.DistanceManager.class)
public interface ForgeSleepForceAccess {
#if MC_VER < MC_1_21_1
    @Accessor(value="forcedTickets",remap=false)
    it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.util.SortedArraySet<net.minecraft.server.level.Ticket<?>>> hassium$sleepForcedTickets();
#endif
}
