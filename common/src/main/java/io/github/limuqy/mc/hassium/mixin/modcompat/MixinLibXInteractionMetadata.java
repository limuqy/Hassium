package io.github.limuqy.mc.hassium.mixin.modcompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Arms the preApply metadata repair before other mods' local-capture injections. */
@Pseudo
@Mixin(targets = "net.minecraft.server.level.ServerPlayerGameMode")
public abstract class MixinLibXInteractionMetadata {}
