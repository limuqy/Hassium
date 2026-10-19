package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pixelmon's gameplay tables belong to the real session, not the cache world's reload. */
@Pseudo
@Mixin(targets = "com.pixelmonmod.pixelmon.init.loaders.PixelmonLoaders", remap = false)
public abstract class MixinPixelmonReloadListeners {
    @Inject(method = "addReloadListeners", at = @At("HEAD"), cancellable = true, remap = false)
    private static void hassium$skipShadowGameplayReload(@Coerce Object event, CallbackInfo ci) {
        if (ShadowAssemblyScope.isActive()) {
            Constants.LOG.info("Hassium: skipped Pixelmon shared gameplay registry reload in shadow assembly");
            ci.cancel();
        }
    }
}
