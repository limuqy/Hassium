package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.compat.ShadowReloadListenersCompat;
import io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope;
import java.util.List;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 影子端 WorldLoader：跳过 recipe/advancement/function/loot reload，保留 TagManager。
 * 门控 {@link ShadowAssemblyScope#isActive()}（只沿影子 WorldLoader 的 executor 传播）。
 */
@Mixin(ReloadableServerResources.class)
public abstract class MixinReloadableServerResources {

    @Inject(method = "listeners", at = @At("RETURN"), cancellable = true)
    private void hassium$shadowSkipNonTagReload(CallbackInfoReturnable<List<PreparableReloadListener>> cir) {
        if (!ShadowAssemblyScope.isActive()) {
            return;
        }
        cir.setReturnValue(ShadowReloadListenersCompat.filterForShadow(cir.getReturnValue()));
    }
}
