package io.github.limuqy.mc.hassium.shadow.storage;

import net.minecraft.nbt.Tag;

/** Opaque worldgen metadata retained by cache-only columns and serialization snapshots. */
public interface ShadowStructureDataAccess {
    Tag hassium$rawStructures();
    void hassium$rawStructures(Tag tag);
}
