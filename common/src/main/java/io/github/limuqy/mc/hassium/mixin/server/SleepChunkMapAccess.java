package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.server.level.ChunkMap.class)
public interface SleepChunkMapAccess {
    @Accessor("entityMap") it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> hassium$sleepEntityMap();
}
