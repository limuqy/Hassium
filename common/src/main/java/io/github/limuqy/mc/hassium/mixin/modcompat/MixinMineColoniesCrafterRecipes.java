package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Colony worker recipes are shared gameplay state and are not consumed by the cache world. */
@Pseudo
@Mixin(targets = "com.minecolonies.core.datalistener.CrafterRecipeListener", remap = false)
public abstract class MixinMineColoniesCrafterRecipes {
    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void hassium$skipShadowCrafterRecipes(CallbackInfo ci) {
        if (ShadowAssemblyScope.isActive()) {
            Constants.LOG.info("Hassium: skipped MineColonies shared crafter recipe reload in shadow assembly");
            ci.cancel();
        }
    }
}
