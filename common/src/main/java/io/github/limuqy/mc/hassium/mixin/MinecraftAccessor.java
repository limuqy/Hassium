package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Minecraft 类的 Accessor，用于获取 FPS 等内部字段
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("fps")
    int hassium$getFps();
}
