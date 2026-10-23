package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The native mesher needs the halo's block/light data, but its BEs must not tick. */
@Mixin(Level.class)
public abstract class MixinSleepLevelTicks {
    @Inject(method="shouldTickBlocksAt(J)Z",at=@At("HEAD"),cancellable=true)
    private void hassium$sleepActiveColumns(long key,CallbackInfoReturnable<Boolean> cir) {
        if((Object)this instanceof ClientLevel && SleepTerrainClient.restricted()
                && !SleepTerrainClient.visible(ChunkPos.getX(key),ChunkPos.getZ(key))) cir.setReturnValue(false);
    }
}
