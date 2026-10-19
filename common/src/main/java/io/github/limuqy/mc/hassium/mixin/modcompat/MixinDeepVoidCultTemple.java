package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The real server owns one-time temple placement; the cache consumes its authoritative chunks. */
@Pseudo
@Mixin(targets = "net.mcreator.thedeepvoid.procedures.CultTemplePlacementProcedure", remap = false)
public abstract class MixinDeepVoidCultTemple {
    @Inject(method = {
            "execute(Lnet/minecraftforge/eventbus/api/Event;Lnet/minecraft/world/level/LevelAccessor;)V",
            "execute(Lnet/neoforged/bus/api/Event;Lnet/minecraft/world/level/LevelAccessor;)V"
    }, at = @At("HEAD"), cancellable = true, remap = false)
    private static void hassium$skipShadowTemplePlacement(@Coerce Object event, @Coerce Object world, CallbackInfo ci) {
        if (world instanceof LevelAccessor level && level.getServer() instanceof ShadowSeedServer) {
            Constants.LOG.info("Hassium: skipped Deep Void one-time temple placement in shadow world");
            ci.cancel();
        }
    }
}
