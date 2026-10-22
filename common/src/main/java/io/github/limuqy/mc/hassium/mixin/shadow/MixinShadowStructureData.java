package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.shadow.storage.ShadowStructureDataAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** No registry values or global/session maps are needed to retain opaque structure NBT. */
@Mixin(ChunkAccess.class)
public class MixinShadowStructureData implements ShadowStructureDataAccess {
    @Unique private Tag hassium$rawStructures;

    @Override public Tag hassium$rawStructures() { return hassium$rawStructures; }
    @Override public void hassium$rawStructures(Tag tag) { hassium$rawStructures = tag; }
}
