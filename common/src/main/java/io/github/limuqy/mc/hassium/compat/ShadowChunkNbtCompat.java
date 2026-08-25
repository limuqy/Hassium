package io.github.limuqy.mc.hassium.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Cross-version removal of derived light state from shadow-only halo snapshots. */
public final class ShadowChunkNbtCompat {
    private ShadowChunkNbtCompat() {}

    public static void stripLightData(CompoundTag chunkTag) {
        chunkTag.remove("isLightOn");
        chunkTag.remove("isLightCorrect");
        chunkTag.remove("LightCorrect");
        stripSections(chunkTag.getList("sections", Tag.TAG_COMPOUND));
        stripSections(chunkTag.getList("Sections", Tag.TAG_COMPOUND));
    }

    private static void stripSections(ListTag sections) {
        for (int index = 0; index < sections.size(); index++) {
            CompoundTag section = sections.getCompound(index);
            section.remove("SkyLight");
            section.remove("BlockLight");
            section.remove("sky_light");
            section.remove("block_light");
        }
    }
}
