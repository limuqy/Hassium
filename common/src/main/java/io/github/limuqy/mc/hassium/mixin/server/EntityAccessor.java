package io.github.limuqy.mc.hassium.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(net.minecraft.world.entity.Entity.class)
public interface EntityAccessor {
    @Invoker("unsetRemoved")
    void hassium$unsetRemoved();
}
