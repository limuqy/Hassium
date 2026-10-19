package io.github.limuqy.mc.hassium.mixin.modcompat;

import net.minecraft.server.ReloadableServerResources;
import org.spongepowered.asm.mixin.Mixin;

/** Runs the constructor-callback scope guard after ordinary mod injections have merged. */
@Mixin(value = ReloadableServerResources.class, priority = 500)
public abstract class MixinShadowCacheResources {}
