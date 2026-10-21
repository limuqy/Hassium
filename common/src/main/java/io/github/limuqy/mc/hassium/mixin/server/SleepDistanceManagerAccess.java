package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.server.level.DistanceManager.class)
public interface SleepDistanceManagerAccess {
#if MC_VER < MC_1_21_2
    @Accessor("tickingTicketsTracker") net.minecraft.server.level.TickingTracker hassium$sleepTickingTracker();
    @Accessor("tickets") it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.util.SortedArraySet<net.minecraft.server.level.Ticket<?>>> hassium$sleepTickets();
#endif
}
