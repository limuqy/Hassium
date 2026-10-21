package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "net.minecraft.server.level.TickingTracker")
public interface SleepTickingTrackerAccess {
#if MC_VER < MC_1_21_2
    @Accessor("tickets") it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.util.SortedArraySet<net.minecraft.server.level.Ticket<?>>> hassium$sleepTickTickets();
#endif
}
