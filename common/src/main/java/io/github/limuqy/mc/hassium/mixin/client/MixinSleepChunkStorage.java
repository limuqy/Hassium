package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.compat.SleepChunkResidency;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepChunkWindow;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public abstract class MixinSleepChunkStorage implements SleepChunkResidency.Storage {
    @Shadow @Final private AtomicReferenceArray<LevelChunk> chunks;
#if MC_VER < MC_1_21_2
    @Shadow protected abstract LevelChunk replace(int index, LevelChunk expected, LevelChunk replacement);
#else
    @Shadow protected abstract void replace(int index, LevelChunk replacement);
#endif

    @Inject(method="<init>",at=@At("RETURN"))
    private void hassium$register(ClientChunkCache owner,int radius,CallbackInfo ci) {
        SleepChunkResidency.register(owner,this);
    }
    @Unique @Override public int hassium$trim(SleepChunkWindow window) {
        int removed=0;
        for(int i=0;i<chunks.length();i++) {
            LevelChunk chunk=chunks.get(i);
            if(chunk!=null && !window.light(chunk.getPos().x,chunk.getPos().z)) {
                var entities=Boolean.getBoolean("hassium.smokeTest")
                        ? new java.util.ArrayList<>(chunk.getBlockEntities().values()) : null;
#if MC_VER < MC_1_21_2
                replace(i,chunk,null);
#else
                replace(i,null);
#endif
                if(entities!=null) {
                    for(var entity:entities) if(!entity.isRemoved())
                        throw new IllegalStateException("Native unload left a live block entity at "+entity.getBlockPos());
                    SleepChunkResidency.noteUnloaded(entities.size());
                }
                removed++;
            }
        }
        return removed;
    }
}
