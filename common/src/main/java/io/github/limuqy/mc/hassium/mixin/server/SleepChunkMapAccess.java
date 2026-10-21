package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(net.minecraft.server.level.ChunkMap.class)
public interface SleepChunkMapAccess {
#if MC_VER < MC_1_21_2
    @Invoker("getDistanceManager") net.minecraft.server.level.DistanceManager hassium$sleepDistanceManager();
    @Invoker("getChunks") Iterable<net.minecraft.server.level.ChunkHolder> hassium$sleepChunks();
    @Invoker("anyPlayerCloseEnoughForSpawning") boolean hassium$sleepActiveChunk(net.minecraft.world.level.ChunkPos pos);
    @Accessor("entityMap") it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> hassium$sleepEntityMap();
#endif
}
