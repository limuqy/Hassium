package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
@org.spongepowered.asm.mixin.Pseudo
public interface SleepTrackedEntityAccess {
    @Accessor("serverEntity") net.minecraft.server.level.ServerEntity hassium$sleepTracker();
    @Accessor("seenBy") java.util.Set<net.minecraft.server.network.ServerPlayerConnection> hassium$sleepObservers();
}
