package io.github.limuqy.mc.hassium.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** Cross-version removal of derived light state from shadow-only halo snapshots. */
public final class ShadowChunkNbtCompat {
    private ShadowChunkNbtCompat() {}

    public static void stripLightData(CompoundTag chunkTag) {
        chunkTag.remove("isLightOn");
        chunkTag.remove("isLightCorrect");
        chunkTag.remove("LightCorrect");
        stripSections(CompoundTagCompat.getList(chunkTag, "sections"));
        stripSections(CompoundTagCompat.getList(chunkTag, "Sections"));
    }

    private static void stripSections(ListTag sections) {
        for (int index = 0; index < sections.size(); index++) {
            CompoundTag section = CompoundTagCompat.getCompound(sections, index);
            if (section == null) {
                continue;
            }
            section.remove("SkyLight");
            section.remove("BlockLight");
            section.remove("sky_light");
            section.remove("block_light");
        }
    }
}
