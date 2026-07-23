package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link LevelLightEngine} 内部 sky/block engine，用于失败后清空 residual light queue。
 */
@Mixin(LevelLightEngine.class)
public interface LevelLightEngineAccessor {

    @Accessor("blockEngine")
    LightEngine<?, ?> hassium$getBlockEngine();

    @Accessor("skyEngine")
    LightEngine<?, ?> hassium$getSkyEngine();
}
