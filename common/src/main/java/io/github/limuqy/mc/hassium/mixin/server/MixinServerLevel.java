package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.compat.ShadowTickOwner;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hassium$bindTickOwnership(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        boolean shadow = self.getServer() instanceof ShadowSeedServer;
        ((ShadowTickOwner) self.getBlockTicks()).hassium$setShadowOwner(shadow);
        ((ShadowTickOwner) self.getFluidTicks()).hassium$setShadowOwner(shadow);
    }
}
