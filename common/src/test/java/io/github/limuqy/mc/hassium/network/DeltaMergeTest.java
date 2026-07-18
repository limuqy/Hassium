package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.cache.client.ChunkDiskCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分段增量 NBT merge 单测。
 * <p>
 * 聚焦不依赖 Minecraft 实例的逻辑：section 替换后 hash 重算、BE 覆盖语义、
 * {@code computeSectionHashesFromNbt} 与 {@code combineSectionHashesFromArray} 一致性。
 * 完整 delta merge 流程（含 LevelChunkSection.read/write）留联机验收。
 */
class DeltaMergeTest {

    @Test
    void computeSectionHashesFromNbtShouldReflectSectionReplacement() {
        // 构造一个含 2 个非空 section 的 NBT
        CompoundTag nbt = buildChunkNbtWithSections(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        long[] before = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);

        // 替换 section 1 的 data
        ListTag sections = (ListTag) nbt.get("sections");
        CompoundTag s1 = (CompoundTag) sections.get(1);
        s1.putByteArray("data", new byte[]{7, 8, 9});
        long[] after = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);

        assertEquals(before[0], after[0], "未变更的 section 0 hash 应保持");
        assertNotEquals(before[1], after[1], "变更的 section 1 hash 应改变");
    }

    @Test
    void computeSectionHashesFromNbtShouldSkipEmptySections() {
        CompoundTag nbt = buildChunkNbtWithSections(new byte[0], new byte[]{1, 2, 3});
        nbt = setSectionHasOnlyAir(nbt, 0, true);
        long[] hashes = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);
        assertEquals(0L, hashes[0], "空 section hash 应为 0");
        assertNotEquals(0L, hashes[1], "非空 section hash 应非 0");
    }

    @Test
    void combineSectionHashesFromArrayShouldMatchAfterMerge() {
        // 模拟 delta merge 后重算 hash 的场景
        CompoundTag nbt = buildChunkNbtWithSections(new byte[]{0x10, 0x20}, new byte[]{0x30, 0x40});
        long[] hashes = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);
        long combined = ChunkContentHashUtil.combineSectionHashesFromArray(hashes);

        // 同样的 section 字节应产生同样的 combined hash（确定性）
        long[] hashes2 = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);
        long combined2 = ChunkContentHashUtil.combineSectionHashesFromArray(hashes2);
        assertEquals(combined, combined2, "同 NBT 重算 hash 应确定");
    }

    @Test
    void emptyDataPlaceholderShouldShortenSectionStream() {
        // 回归：ensureSectionsSize 若用 data=[]，nbtToPacketBytes 会少写字节导致虚空
        CompoundTag withEmpty = buildChunkNbtWithSections(new byte[0], new byte[]{1, 2, 3});
        CompoundTag withPad = buildChunkNbtWithSections(new byte[]{0}, new byte[]{1, 2, 3});
        byte[] pktEmpty = ChunkDiskCodec.nbtToPacketBytes(withEmpty, null, 2);
        byte[] pktPad = ChunkDiskCodec.nbtToPacketBytes(withPad, null, 2);
        assertNotNull(pktEmpty);
        assertNotNull(pktPad);
        assertTrue(pktPad.length > pktEmpty.length,
                "占位 data 非空时应写出更长的 sections 流（禁止 data=[] 占位）");
    }

    @Test
    void clearingSectionToAirShouldZeroHashAndChangeCombine() {
        CompoundTag nbt = buildChunkNbtWithSections(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        long[] before = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);
        long combineBefore = ChunkContentHashUtil.combineSectionHashesFromArray(before);

        // 模拟服务端「变空气」delta：标记 has_only_air 并清空 data（hash 路径跳过）
        ListTag sections = (ListTag) nbt.get("sections");
        CompoundTag s1 = (CompoundTag) sections.get(1);
        s1.putBoolean("has_only_air", true);
        s1.putByteArray("data", new byte[0]);

        long[] after = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);
        long combineAfter = ChunkContentHashUtil.combineSectionHashesFromArray(after);

        assertEquals(0L, after[1], "清除后的 section hash 应为 0");
        assertEquals(before[0], after[0], "未变更 section 0 应保持");
        assertNotEquals(combineBefore, combineAfter, "清除 section 后 combine 应变化");
    }

    @Test
    void sectionHashRequestPacketShouldRoundTrip() {
        SectionHashRequestC2SPacket original = new SectionHashRequestC2SPacket(
                "minecraft:overworld",
                List.of(new SectionHashRequestC2SPacket.Entry(3, -7, new long[]{0L, 1L, 2L})));
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            original.encode(buf);
            SectionHashRequestC2SPacket decoded = SectionHashRequestC2SPacket.decode(buf);
            assertEquals(original.dimension(), decoded.dimension());
            assertEquals(1, decoded.entries().size());
            assertEquals(3, decoded.entries().get(0).chunkX());
            assertEquals(-7, decoded.entries().get(0).chunkZ());
            assertArrayEquals(new long[]{0L, 1L, 2L}, decoded.entries().get(0).sectionHashes());
        } finally {
            buf.release();
        }
    }

    @Test
    void sectionDeltaPacketShouldRoundTripSections() {
        SectionDeltaS2CPacket original = new SectionDeltaS2CPacket(
                "minecraft:overworld",
                List.of(new SectionDeltaS2CPacket.DeltaEntry(
                        1, 2,
                        List.of(new SectionDeltaS2CPacket.SectionData(5, new byte[]{9, 8, 7})),
                        List.of())),
                List.of(new SectionDeltaS2CPacket.SkippedChunk(8, 6)));
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            original.encode(buf);
            SectionDeltaS2CPacket decoded = SectionDeltaS2CPacket.decode(buf);
            assertEquals(original.dimension(), decoded.dimension());
            assertEquals(1, decoded.entries().size());
            var e = decoded.entries().get(0);
            assertEquals(1, e.chunkX());
            assertEquals(2, e.chunkZ());
            assertEquals(1, e.changedSections().size());
            assertEquals(5, e.changedSections().get(0).sectionIndex());
            assertArrayEquals(new byte[]{9, 8, 7}, e.changedSections().get(0).blockData());
            assertTrue(e.blockEntities().isEmpty());
            assertEquals(1, decoded.skipped().size());
            assertEquals(8, decoded.skipped().get(0).chunkX());
            assertEquals(6, decoded.skipped().get(0).chunkZ());
        } finally {
            buf.release();
        }
    }

    @Test
    void beListReplacementShouldFullyOverride() {
        // 模拟 delta BE 覆盖：原 BE 列表被完全替换
        CompoundTag nbt = buildChunkNbtWithSections(new byte[]{1}, new byte[]{2});
        ListTag originalBe = new ListTag();
        originalBe.add(makeBeNbt("old_be", 1, 2, 3));
        nbt.put("block_entities", originalBe);

        // delta BE 列表（全量覆盖）
        ListTag deltaBe = new ListTag();
        deltaBe.add(makeBeNbt("new_be_1", 10, 20, 30));
        deltaBe.add(makeBeNbt("new_be_2", 40, 50, 60));
        nbt.put("block_entities", deltaBe);

        ListTag result = (ListTag) nbt.get("block_entities");
        assertEquals(2, result.size(), "BE 列表应被完全替换为 delta 列表");
        CompoundTag be0 = (CompoundTag) result.get(0);
        // 1.21.5+ getString 返回 Optional，直接检查 StringTag
        Tag idTag = be0.get("id");
        assertTrue(idTag instanceof net.minecraft.nbt.StringTag, "id 应为 StringTag");
        assertEquals("new_be_1",
                io.github.limuqy.mc.hassium.compat.CompoundTagCompat.getString(
                        (net.minecraft.nbt.StringTag) idTag));
    }

    /** 构造含 2 个非空 section 的 chunk NBT fixture。 */
    private static CompoundTag buildChunkNbtWithSections(byte[] data0, byte[] data1) {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("x", 0);
        nbt.putInt("z", 0);
        nbt.putInt("section_count", 24);

        ListTag sections = new ListTag();
        CompoundTag s0 = new CompoundTag();
        s0.putBoolean("has_only_air", data0.length == 0);
        s0.putByteArray("data", data0);
        sections.add(s0);
        CompoundTag s1 = new CompoundTag();
        s1.putBoolean("has_only_air", data1.length == 0);
        s1.putByteArray("data", data1);
        sections.add(s1);
        nbt.put("sections", sections);

        nbt.put("heightmaps", new CompoundTag());
        nbt.put("block_entities", new ListTag());
        nbt.putByte("is_light_on", (byte) 0);
        return nbt;
    }

    private static CompoundTag setSectionHasOnlyAir(CompoundTag nbt, int idx, boolean hasOnlyAir) {
        ListTag sections = (ListTag) nbt.get("sections");
        ((CompoundTag) sections.get(idx)).putBoolean("has_only_air", hasOnlyAir);
        return nbt;
    }

    private static CompoundTag makeBeNbt(String id, int x, int y, int z) {
        CompoundTag be = new CompoundTag();
        be.putString("id", id);
        be.putInt("x", x);
        be.putInt("y", y);
        be.putInt("z", z);
        return be;
    }
}
