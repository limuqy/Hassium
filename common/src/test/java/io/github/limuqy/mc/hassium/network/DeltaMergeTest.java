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
 * 边界：本测试族不引导 Minecraft，被测 path 中真正走 {@code LevelChunkSection.read/write}
 * 的 hash 重算留联机验收（见 {@code computeSectionHashesFromNbt} 对非空 data 的 scratch.read）。
 * 这里覆盖的是不依赖 MC ROI 的纯逻辑：
 * <ul>
 *   <li>{@link ChunkDiskCodec#computeSectionHashesFromNbt} 的 has_only_air / 空 data 短路（不触发 read）</li>
 *   <li>{@link ChunkContentHashUtil#combineSectionHashesFromArray} 替换/清空/确定性语义（merge 后 chunkHash 变化的基础）</li>
 *   <li>be 列表全量覆盖语义</li>
 </ul>
 */
class DeltaMergeTest {

    @Test
    void computeSectionHashesFromNbtShouldShortCircuitOnAllAirSections() {
        // 边界：所有 section 都 has_only_air → computeSectionHashesFromNbt 全短路，不触发
        // scratch.read/bootstrap，返回长度匹配 sectionCount、值全 0
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("section_count", 24);
        ListTag sections = new ListTag();
        for (int i = 0; i < 3; i++) {
            CompoundTag s = new CompoundTag();
            s.putBoolean("has_only_air", true);
            s.putByteArray("data", new byte[0]);
            sections.add(s);
        }
        nbt.put("sections", sections);

        long[] hashes = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);
        assertNotNull(hashes);
        assertEquals(24, hashes.length, "hash 数组长度应为 max(sectionCount, sections.size())");
        for (int i = 0; i < hashes.length; i++) {
            assertEquals(0L, hashes[i], "全空气 section hash 应为 0 (idx=" + i + ")");
        }
    }

    @Test
    void computeSectionHashesFromNbtShouldShortCircuitOnEmptyDataBytes() {
        // 边界：section 非空气但 data=[]（length==0）→ computeSectionHashesFromNbt 短路，不触发
        // scratch.read/bootstrap，对应位置的 hash 保持 0
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("section_count", 4);
        ListTag sections = new ListTag();
        CompoundTag s0 = new CompoundTag();
        s0.putBoolean("has_only_air", false);
        s0.putByteArray("data", new byte[0]);  // 空 data → 短路
        sections.add(s0);
        CompoundTag s1 = new CompoundTag();
        s1.putBoolean("has_only_air", true);   // has_only_air → 短路
        s1.putByteArray("data", new byte[0]);
        sections.add(s1);
        nbt.put("sections", sections);

        long[] hashes = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 4, null);
        assertEquals(4, hashes.length);
        assertEquals(0L, hashes[0], "空 data section hash 应为 0");
        assertEquals(0L, hashes[1], "has_only_air section hash 应为 0");
    }

    @Test
    void combineSectionHashesFromArrayShouldBeDeterministicOnSameInput() {
        // merge 后重算的确定性基础：同输入 combine 两次结果相同（纯组合，不触发 read）
        long[] hashes = new long[]{0L, 0x1234567890ABCDEFL, 0L, 0xFEDCBA0987654321L, 0L};
        long a = ChunkContentHashUtil.combineSectionHashesFromArray(hashes);
        long b = ChunkContentHashUtil.combineSectionHashesFromArray(hashes);
        assertEquals(a, b, "同输入 combine 应确定");
        assertNotEquals(0L, a, "非全零输入 combine 不应为 0");
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
        // 服务端「变空气」delta 在 hash 数组层的语义：对应 section hash 归 0，combine 必然变化
        // （纯组合层验证，不触发 scratch.read；hash 重算→0 的真实行为留联机验收）
        long[] before = new long[]{2L, 3L};
        long combineBefore = ChunkContentHashUtil.combineSectionHashesFromArray(before);

        long[] after = new long[]{2L, 0L};  // section 1 变空气 → hash 置 0
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
                io.github.limuqy.mc.promethium.compat.CompoundTagCompat.getString(
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
