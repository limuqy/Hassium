package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.shadow.storage.ShadowStructureDataAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowStructureDataCompatTest {
    @Test void cacheReadOmitsWorldgenMetadataWithoutChangingTheInputOrOtherPayloads() {
        var root = root();
        var original = root.copy();
        var readable = ShadowStructureDataCompat.forRead(root, true);
        assertFalse(readable.contains("structures"));
        assertEquals(original, root);
        assertSame(root.get("sections"), readable.get("sections"));
        assertSame(root.get("block_entities"), readable.get("block_entities"));
        assertSame(root.get("isLightOn"), readable.get("isLightOn"));
    }

    @Test void seedgenAndNormalWorldReadsKeepNativeStructureParsing() {
        var root = root();
        assertSame(root, ShadowStructureDataCompat.forRead(root, false));
        assertNotNull(root.get("structures"));
    }

    @Test void readsWithoutStructureDataNeedNoRootCopy() {
        var root = new CompoundTag();
        root.putBoolean("isLightOn", true);
        assertSame(root, ShadowStructureDataCompat.forRead(root, true));
    }

    @Test void opaqueUnknownIdsAndExtraFieldsRoundTripWithoutAliasing() throws Exception {
        var root = root();
        var original = root.get("structures").copy();
        var chunk = fixture();
        ShadowStructureDataCompat.retain(chunk, root);
        ((CompoundTag) root.get("structures")).putString("later_input_change", "ignored");
        var output = new CompoundTag();
        output.put("structures", new CompoundTag()); // Native serialization of empty structure maps.
        ShadowStructureDataCompat.writeBack(output, ShadowStructureDataCompat.snapshot(chunk));
        assertEquals(original, output.get("structures"));
        ((CompoundTag) output.get("structures")).putString("later_output_change", "ignored");
        assertEquals(original, ShadowStructureDataCompat.snapshot(chunk));
    }

    @Test void imposterUsesTheWrappedColumnAndPromotionOrFullReplacementCopiesMetadata() throws Exception {
        var from = fixture();
        var wrapper = (ImposterProtoChunk) unsafe().allocateInstance(ImposterProtoChunk.class);
        var wrapped = ImposterProtoChunk.class.getDeclaredField("wrapped"); wrapped.setAccessible(true);
        wrapped.set(wrapper, from);
        ShadowStructureDataCompat.retain(wrapper, root());
        assertEquals(root().get("structures"), ShadowStructureDataCompat.snapshot(from));
        var promoted = fixture();
        ShadowStructureDataCompat.transfer(wrapper, promoted);
        assertEquals(ShadowStructureDataCompat.snapshot(from), ShadowStructureDataCompat.snapshot(promoted));
        assertNotSame(from.raw, promoted.raw);
    }

    @Test void serializationSnapshotSurvivesLaterColumnChanges() throws Exception {
        var chunk = fixture();
        ShadowStructureDataCompat.retain(chunk, root());
        Tag snapshot = ShadowStructureDataCompat.snapshot(chunk);
        var expected = snapshot.copy();
        ShadowStructureDataCompat.retain(chunk, new CompoundTag());
        var output = new CompoundTag();
        ShadowStructureDataCompat.writeBack(output, snapshot);
        assertEquals(expected, output.get("structures"));
        assertNull(ShadowStructureDataCompat.snapshot(chunk));
    }

    @Test void noOpaqueDataLeavesNativeSaveDataUntouchedAndEmptyCompoundIsPreserved() throws Exception {
        var chunk = fixture();
        var nativeOutput = root();
        var expected = nativeOutput.copy();
        ShadowStructureDataCompat.writeBack(nativeOutput, ShadowStructureDataCompat.snapshot(chunk));
        assertEquals(expected, nativeOutput);
        var root = new CompoundTag(); root.put("structures", new CompoundTag());
        ShadowStructureDataCompat.retain(chunk, root);
        ShadowStructureDataCompat.writeBack(nativeOutput, ShadowStructureDataCompat.snapshot(chunk));
        assertEquals(new CompoundTag(), nativeOutput.get("structures"));
    }

    private static CompoundTag root() {
        var start = new CompoundTag();
        start.putString("id", "uninstalled_mod:opaque_structure");
        start.putByteArray("unknown_payload", new byte[]{0, -1, 42});
        var starts = new CompoundTag(); starts.put("uninstalled_mod:opaque_structure", start);
        var refs = new CompoundTag(); refs.putLongArray("minecraft:mineshaft", new long[]{1L, Long.MIN_VALUE});
        var structures = new CompoundTag(); structures.put("starts", starts); structures.put("References", refs);
        structures.putString("custom_extension", "must survive verbatim");
        var root = new CompoundTag(); root.put("structures", structures);
        root.put("sections", new net.minecraft.nbt.ListTag());
        root.put("block_entities", new net.minecraft.nbt.ListTag());
        root.putBoolean("isLightOn", true);
        return root;
    }

    private static FixtureChunk fixture() throws Exception {
        return (FixtureChunk) unsafe().allocateInstance(FixtureChunk.class);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static class FixtureChunk extends LevelChunk implements ShadowStructureDataAccess {
        Tag raw;
        private FixtureChunk() { super(null, new ChunkPos(0, 0)); }
        @Override public Tag hassium$rawStructures() { return raw; }
        @Override public void hassium$rawStructures(Tag tag) { raw = tag; }
    }
}
