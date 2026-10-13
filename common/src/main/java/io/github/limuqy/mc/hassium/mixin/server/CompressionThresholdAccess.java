package io.github.limuqy.mc.hassium.mixin.server;

import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompressionEncoder.class)
public interface CompressionThresholdAccess {
    @Accessor("threshold") int hassium$getThreshold();
}
