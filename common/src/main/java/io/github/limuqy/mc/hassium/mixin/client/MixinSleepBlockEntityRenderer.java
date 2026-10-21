package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Includes block entities rendered outside the normal compiled-section list. */
@Mixin(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class)
public abstract class MixinSleepBlockEntityRenderer {
#if MC_VER < MC_1_21_2
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void hassium$visible(net.minecraft.world.level.block.entity.BlockEntity entity,float tick,
            com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.client.renderer.MultiBufferSource buffers,CallbackInfo ci) {
        var pos=entity.getBlockPos();
        if(!SleepTerrainClient.visible(pos.getX()>>4,pos.getZ()>>4)) ci.cancel();
    }
#endif
}
