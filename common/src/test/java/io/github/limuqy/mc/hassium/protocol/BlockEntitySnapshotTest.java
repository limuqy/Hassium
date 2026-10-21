package io.github.limuqy.mc.hassium.protocol;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockEntitySnapshotTest {
    @Test void emptySnapshotIsExplicitAndNotLegacyAbsence() {
        assertEquals(List.of(), BlockEntitySnapshot.decode(BlockEntitySnapshot.encode(List.of()), new ChunkPos(0,0)));
        assertThrows(IllegalArgumentException.class, () -> BlockEntitySnapshot.decode(new byte[0], new ChunkPos(0,0)));
    }
    @Test void roundTripPreservesNullEmptyNestedTagsAndNegativeCoordinates() {
        var tag = new CompoundTag();
        var nested = new CompoundTag(); nested.putString("text", "NBT 回放"); tag.put("nested", nested);
        var entries = List.of(
                new SectionDeltaS2CPacket.BlockEntityData(new BlockPos(-1,68,-16), "fixture:sign", tag),
                new SectionDeltaS2CPacket.BlockEntityData(new BlockPos(-2,68,-16), "fixture:null", null),
                new SectionDeltaS2CPacket.BlockEntityData(new BlockPos(-3,68,-16), "fixture:empty", new CompoundTag()));
        assertEquals(entries, BlockEntitySnapshot.decode(BlockEntitySnapshot.encode(entries), new ChunkPos(-1,-1)));
    }
    @Test void rejectsForeignDuplicateAndTrailingData() {
        var entry = new SectionDeltaS2CPacket.BlockEntityData(BlockPos.ZERO, "fixture:be", new CompoundTag());
        assertThrows(IllegalArgumentException.class, () -> BlockEntitySnapshot.decode(BlockEntitySnapshot.encode(List.of(entry)), new ChunkPos(1,0)));
        assertThrows(IllegalArgumentException.class, () -> BlockEntitySnapshot.decode(BlockEntitySnapshot.encode(List.of(entry,entry)), new ChunkPos(0,0)));
        assertThrows(IllegalArgumentException.class, () -> BlockEntitySnapshot.decode(new byte[]{0,1}, new ChunkPos(0,0)));
    }
}
