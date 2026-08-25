package io.github.limuqy.mc.hassium.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class ShadowChunkNbtCompatTest {
    @Test
    void removesOnlyDerivedLightDataFromBothSectionLayouts() {
        CompoundTag chunk = new CompoundTag();
        chunk.putBoolean("isLightOn", true);
        chunk.putBoolean("isLightCorrect", true);
        chunk.putString("Status", "full");
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByteArray("SkyLight", new byte[2048]);
        section.putByteArray("BlockLight", new byte[2048]);
        section.putString("block_states", "retain");
        sections.add(section);
        chunk.put("sections", sections);

        ShadowChunkNbtCompat.stripLightData(chunk);

        assertFalse(chunk.contains("isLightOn"));
        assertFalse(chunk.contains("isLightCorrect"));
        assertTrue(chunk.contains("Status"));
        assertFalse(section.contains("SkyLight"));
        assertFalse(section.contains("BlockLight"));
        assertTrue(section.contains("block_states"));
    }
}
