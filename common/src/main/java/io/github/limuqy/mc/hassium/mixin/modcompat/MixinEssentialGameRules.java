package io.github.limuqy.mc.hassium.mixin.modcompat;

#if MC_VER < MC_1_21_11
import net.minecraft.world.level.GameRules;
#else
import net.minecraft.world.level.gamerules.GameRules;
#endif
import org.spongepowered.asm.mixin.Mixin;

/** Lower priority runs the postApply guard after Essential has merged its callback. */
#if MC_VER < MC_1_21_11
@Mixin(value = GameRules.Value.class, priority = 500)
#else
@Mixin(value = GameRules.class, priority = 500)
#endif
public abstract class MixinEssentialGameRules {
}
