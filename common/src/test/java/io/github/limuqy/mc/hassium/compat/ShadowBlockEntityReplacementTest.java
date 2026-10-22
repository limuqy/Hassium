package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.mixin.shadow.LevelChunkPendingBeAccessor;
import io.github.limuqy.mc.hassium.protocol.SectionDeltaS2CPacket;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowBlockEntitySnapshotAccess;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowBlockEntitySnapshots;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowBlockEntityReplacementTest {
    private static final BlockPos POS = new BlockPos(6, 48, 5);

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void vanillaRemovalLeavesEntityInDetachedServerChunk() throws Exception {
        var chunk = fixture();
        chunk.finishRemoval = false;
        var old = chest(chunk);
        chunk.removeBlockEntity(POS);
        assertSame(old, chunk.getBlockEntities().get(POS), "Reproduces vanilla loaded=false removal semantics");
    }

    @Test void emptyAuthoritySnapshotRemovesLivePendingAndReceivedData() throws Exception {
        var chunk = fixture();
        var old = chest(chunk);
        chunk.hassium$getPendingBlockEntities().put(POS, tag("minecraft:chest"));
        chunk.hassium$blockEntitySnapshots().put(POS, "minecraft:chest", null);
        chunk.states.put(POS, Blocks.WATER.defaultBlockState());
        ShadowBlockEntityCompat.applySnapshot(chunk, List.of());
        assertTrue(chunk.getBlockEntities().isEmpty());
        assertTrue(chunk.hassium$getPendingBlockEntities().isEmpty());
        assertEquals(0, chunk.hassium$blockEntitySnapshots().size());
        assertTrue(old.isRemoved());
    }

    @Test void changedEntityTypeReplacesDetachedInstanceWithoutPromotingOldPendingTag() throws Exception {
        var chunk = fixture();
        var old = chest(chunk);
        chunk.hassium$getPendingBlockEntities().put(POS, tag("minecraft:chest"));
        chunk.states.put(POS, Blocks.BARREL.defaultBlockState());
        ShadowBlockEntityCompat.applySnapshot(chunk,
                List.of(new SectionDeltaS2CPacket.BlockEntityData(POS, "minecraft:barrel", null)));
        assertTrue(old.isRemoved());
        assertEquals(BlockEntityType.BARREL, chunk.getBlockEntity(POS).getType());
        assertTrue(chunk.hassium$getPendingBlockEntities().isEmpty());
        assertEquals(1, chunk.hassium$blockEntitySnapshots().countType("minecraft:barrel"));
    }

    @Test void invalidAuthoritySnapshotDoesNotPartiallyClearExistingData() throws Exception {
        var chunk = fixture();
        var old = chest(chunk);
        chunk.hassium$blockEntitySnapshots().put(POS, "minecraft:chest", null);
        assertThrows(IllegalArgumentException.class, () -> ShadowBlockEntityCompat.applySnapshot(chunk,
                List.of(new SectionDeltaS2CPacket.BlockEntityData(POS, "minecraft:barrel", null))));
        assertSame(old, chunk.getBlockEntities().get(POS));
        assertFalse(old.isRemoved());
        assertEquals(1, chunk.hassium$blockEntitySnapshots().size());
    }

    @Test void validInstanceSurvivesWhileStalePendingTagAtSamePositionIsDiscarded() throws Exception {
        var chunk = fixture();
        var existing = chest(chunk);
        chunk.hassium$getPendingBlockEntities().put(POS, tag("minecraft:barrel"));
        ShadowBlockEntityCompat.applySnapshot(chunk,
                List.of(new SectionDeltaS2CPacket.BlockEntityData(POS, "minecraft:chest", null)));
        assertSame(existing, chunk.getBlockEntity(POS));
        assertFalse(existing.isRemoved());
        assertTrue(chunk.hassium$getPendingBlockEntities().isEmpty());
    }

    @Test void cachedOrphansArePrunedButValidFullAndClientNbtAreUntouched() throws Exception {
        var chunk = fixture();
        var list = new ListTag();
        var valid = tag("minecraft:chest");
        valid.putString("custom_mod_data", "preserve verbatim");
        chunk.states.put(POS, Blocks.CHEST.defaultBlockState());
        list.add(valid);
        var marked = tag("minecraft:barrel"); marked.putInt("x", 7);
        marked.putBoolean(ShadowBlockEntitySnapshots.MARKER, true);
        chunk.states.put(new BlockPos(7, 48, 5), Blocks.BARREL.defaultBlockState());
        list.add(marked);
        var stale = tag("minecraft:brushable_block"); stale.putInt("x", 8);
        chunk.states.put(new BlockPos(8, 48, 5), Blocks.WATER.defaultBlockState()); list.add(stale);
        var stalePacked = tag("minecraft:mob_spawner"); stalePacked.putInt("x", 9);
        stalePacked.putBoolean("keepPacked", true); list.add(stalePacked);
        var wrongColumn = tag("minecraft:chest"); wrongColumn.putInt("x", 22);
        chunk.states.put(new BlockPos(22, 48, 5), Blocks.CHEST.defaultBlockState()); list.add(wrongColumn);
        var root = new CompoundTag(); root.put("block_entities", list); root.putString("Status", "minecraft:full");
        var validCopy = valid.copy(); var markedCopy = marked.copy();
        assertEquals(3, ShadowBlockEntityCompat.pruneInconsistentSavedTags(chunk, root));
        assertEquals(2, list.size());
        assertEquals(validCopy, list.get(0)); assertEquals(markedCopy, list.get(1));
        assertEquals(0, ShadowBlockEntityCompat.pruneInconsistentSavedTags(chunk, root));
        var restored = new ShadowBlockEntitySnapshots();
        ShadowBlockEntityCompat.restore(restored, root);
        assertEquals(1, restored.restoredCount());
    }

    @Test void dummyNbtNeedsAnEntityBlockAndInvalidIdsCannotBePromoted() {
        assertTrue(BlockEntityCompat.canPromote(tag("DUMMY"), Blocks.CHEST.defaultBlockState()));
        assertFalse(BlockEntityCompat.canPromote(tag("DUMMY"), Blocks.AIR.defaultBlockState()));
        assertFalse(BlockEntityCompat.canPromote(tag("unknown:missing"), Blocks.CHEST.defaultBlockState()));
        assertFalse(BlockEntityCompat.canPromote(tag("invalid id"), Blocks.CHEST.defaultBlockState()));
    }

    private static BlockEntity chest(FixtureChunk chunk) {
        chunk.states.put(POS, Blocks.CHEST.defaultBlockState());
        var be = BlockEntityType.CHEST.create(POS, Blocks.CHEST.defaultBlockState());
        chunk.getBlockEntities().put(POS, be);
        return be;
    }

    private static CompoundTag tag(String type) {
        var tag = new CompoundTag();
        tag.putString("id", type);
        tag.putInt("x", POS.getX()); tag.putInt("y", POS.getY()); tag.putInt("z", POS.getZ());
        return tag;
    }

    // Use real LevelChunk removal/creation methods without starting a world or applying Mixins.
    private static FixtureChunk fixture() throws Exception {
        var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) f.get(null);
        var chunk = (FixtureChunk) unsafe.allocateInstance(FixtureChunk.class);
        set(LevelChunk.class, chunk, "level", unsafe.allocateInstance(ServerLevel.class));
        set(ChunkAccess.class, chunk, "blockEntities", new HashMap<>());
        set(LevelChunk.class, chunk, "tickersInLevel", new HashMap<>());
        set(ChunkAccess.class, chunk, "pendingBlockEntities", new HashMap<>());
        set(ChunkAccess.class, chunk, "chunkPos", new ChunkPos(0, 0));
        chunk.states = new HashMap<>();
        chunk.snapshots = new ShadowBlockEntitySnapshots();
        chunk.finishRemoval = true;
        return chunk;
    }

    private static void set(Class<?> owner, Object obj, String name, Object value) throws Exception {
        var f = owner.getDeclaredField(name); f.setAccessible(true); f.set(obj, value);
    }

    private static class FixtureChunk extends LevelChunk implements LevelChunkPendingBeAccessor, ShadowBlockEntitySnapshotAccess {
        Map<BlockPos, BlockState> states;
        ShadowBlockEntitySnapshots snapshots;
        boolean finishRemoval;
        private FixtureChunk() { super(null, new ChunkPos(0, 0)); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public Map<BlockPos, CompoundTag> hassium$getPendingBlockEntities() { return pendingBlockEntities; }
        @Override public ShadowBlockEntitySnapshots hassium$blockEntitySnapshots() { return snapshots; }
        @Override public void removeBlockEntity(BlockPos pos) {
            super.removeBlockEntity(pos);
            // Same RETURN hook as MixinShadowBlockEntitySnapshots, with no Mixin runtime in JUnit.
            if (finishRemoval) ShadowBlockEntityCompat.finishRemoval(this, pos);
        }
    }
}
