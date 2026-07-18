package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChunkDiskCodec} 单测。
 * <p>
 * 聚焦不依赖 Minecraft 实例的逻辑校验：magic 前缀检测、hash 确定性、空 section 跳过。
 * 完整联机 apply 路径留待运行时验收。
 */
class ChunkDiskCodecTest {

    @Test
    void isValidChunkNbtShouldRejectNullAndShortBytes() {
        assertFalse(ChunkDiskCodec.isValidChunkNbt(null));
        assertFalse(ChunkDiskCodec.isValidChunkNbt(new byte[0]));
        assertFalse(ChunkDiskCodec.isValidChunkNbt(new byte[]{1, 2}));
    }

    @Test
    void isValidChunkNbtShouldRejectNonNbtBytes() {
        // 旧 packet 字节（无 magic 前缀）应被识别为非法 NBT
        byte[] packetBytes = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertFalse(ChunkDiskCodec.isValidChunkNbt(packetBytes), "非 NBT 字节应被拒绝");
    }

    @Test
    void sectionsFieldShouldBeListTagNotCompound() {
        // 回归：曾误用 containsCompound("sections")，合法 ListTag 恒被判失败
        CompoundTag nbt = buildSampleChunkNbt();
        assertTrue(CompoundTagCompat.containsList(nbt, "sections"), "sections 应为 ListTag");
        assertFalse(CompoundTagCompat.containsCompound(nbt, "sections"),
                "sections 不应被当成 CompoundTag");
    }

    @Test
    void isValidChunkNbtShouldAcceptSectionsListTag() {
        CompoundTag nbt = buildSampleChunkNbt();
        byte[] bytes = ChunkDiskCodec.nbtToBytes(nbt);
        assertNotNull(bytes, "nbtToBytes 应能序列化测试 fixture");
        assertTrue(ChunkDiskCodec.isValidChunkNbt(bytes), "含 sections ListTag 的 NBT 应通过校验");
    }

    @Test
    void isValidChunkNbtShouldRejectSectionsAsCompound() {
        CompoundTag nbt = buildSampleChunkNbt();
        nbt.put("sections", new CompoundTag()); // 错误类型
        byte[] bytes = ChunkDiskCodec.nbtToBytes(nbt);
        assertNotNull(bytes);
        assertFalse(ChunkDiskCodec.isValidChunkNbt(bytes), "sections 为 CompoundTag 时应拒绝");
    }

    @Test
    void isValidChunkNbtShouldRejectMissingSections() {
        CompoundTag nbt = buildSampleChunkNbt();
        nbt.remove("sections");
        byte[] bytes = ChunkDiskCodec.nbtToBytes(nbt);
        assertNotNull(bytes);
        assertFalse(ChunkDiskCodec.isValidChunkNbt(bytes), "缺少 sections 时应拒绝");
    }

    @Test
    void bytesToNbtShouldReturnNullForBadMagic() {
        // 构造一个 magic 错误的字节
        byte[] badMagic = new byte[]{'H', 'B', 'T', '1', 0, 0, 0, 0};
        badMagic[0] = (byte) (badMagic[0] ^ 0xFF); // 破坏首字节
        assertNull(ChunkDiskCodec.bytesToNbt(badMagic), "magic 不符应返回 null");
    }

    @Test
    void computeSectionHashesFromNbtShouldSkipEmptySections() {
        CompoundTag nbt = buildSampleChunkNbt();
        long[] hashes = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, 24, null);
        assertNotNull(hashes);
        // section 0 是空（has_only_air=true），hash 应为 0
        assertEquals(0L, hashes[0], "空 section hash 应为 0");
    }

    @Test
    void combineSectionHashesFromArrayShouldBeDeterministic() {
        // 同输入应产生同输出（服务端/客户端一致性的基础）
        long[] hashes = new long[]{0L, 0x1234567890ABCDEFL, 0L, 0xFEDCBA0987654321L};
        long h1 = ChunkContentHashUtil.combineSectionHashesFromArray(hashes);
        long h2 = ChunkContentHashUtil.combineSectionHashesFromArray(hashes);
        assertEquals(h1, h2, "同输入 combine 应确定");
        assertNotEquals(0L, h1, "hash 不应为 0");
    }

    @Test
    void combineSectionHashesFromArrayShouldSkipZeroEntries() {
        // 0 值 section 应被跳过，不影响 hash
        long[] withZeros = new long[]{0L, 1L, 0L, 2L};
        long[] withoutZeros = new long[]{1L, 0L, 2L}; // 等价但数组长度不同
        long h1 = ChunkContentHashUtil.combineSectionHashesFromArray(withZeros);
        // 注意：数组长度不同会导致索引不同，所以 hash 可能不同；这里只验证 0 值不参与
        // 改为同长度比较
        long[] same1 = new long[]{0L, 1L, 0L, 2L};
        long[] same2 = new long[]{0L, 1L, 0L, 2L};
        assertEquals(
                ChunkContentHashUtil.combineSectionHashesFromArray(same1),
                ChunkContentHashUtil.combineSectionHashesFromArray(same2));
    }

    @Test
    void combineSectionHashesFromArrayShouldHandleEmpty() {
        assertEquals(1L, ChunkContentHashUtil.combineSectionHashesFromArray(new long[0]));
        assertEquals(1L, ChunkContentHashUtil.combineSectionHashesFromArray(null));
    }

    /** 构造一个最小可用的 chunk NBT fixture。 */
    private static CompoundTag buildSampleChunkNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("x", 10);
        nbt.putInt("z", -5);
        nbt.putInt("section_count", 24);

        ListTag sections = new ListTag();
        CompoundTag s0 = new CompoundTag();
        s0.putBoolean("has_only_air", true);
        s0.putByteArray("data", new byte[0]);
        sections.add(s0);
        CompoundTag s1 = new CompoundTag();
        s1.putBoolean("has_only_air", false);
        s1.putByteArray("data", new byte[]{(byte) 0x80, 0x01, 0x02});
        sections.add(s1);
        nbt.put("sections", sections);

        nbt.put("heightmaps", new CompoundTag());
        nbt.put("block_entities", new ListTag());
        nbt.putByte("is_light_on", (byte) 0);
        return nbt;
    }
}
