package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cache-only shadows never assemble worldgen modifiers from unsynced registries. */
@Pseudo
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.modifier.ModifierManager", remap = false)
public abstract class MixinLithostitchedModifiers {
    @Inject(method = "getAllModifiers", at = @At("HEAD"), cancellable = true, remap = false)
    private static void hassium$skipCacheOnlyWorldgen(CallbackInfoReturnable<Map<?, ?>> cir) {
        if (ShadowAssemblyScope.isCacheOnlyAssembly()) cir.setReturnValue(Map.of());
    }
}
