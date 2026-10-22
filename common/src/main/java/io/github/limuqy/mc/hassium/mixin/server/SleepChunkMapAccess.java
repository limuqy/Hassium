package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.server.level.ChunkMap.class)
public interface SleepChunkMapAccess {
#if MC_VER < MC_1_21_2
    @Accessor("entityMap") it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> hassium$sleepEntityMap();
#endif
}
