package io.github.limuqy.mc.hassium.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 权威边沿载荷编解码 + 服务端权威 hash 缓存（L0：无 MC 实例）。 */
class ChunkAuthorityS2CPacketTest {

    @Test
    @DisplayName("enter 通知编解码往返：维度/epoch/snapshot/hash 全字段保真")
    void roundTripEnterNotification() {
        ChunkAuthorityS2CPacket original = new ChunkAuthorityS2CPacket(
                "minecraft:overworld", 7L, false,
                List.of(new ChunkAuthorityS2CPacket.Entry(3, -5, 1234567890123L),
                        new ChunkAuthorityS2CPacket.Entry(-1, 2, 0L)));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(buf);
            ChunkAuthorityS2CPacket decoded = ChunkAuthorityS2CPacket.decode(buf);
            assertEquals(original.dimension(), decoded.dimension());
            assertEquals(7L, decoded.epoch());
            assertFalse(decoded.snapshot());
            assertEquals(2, decoded.entries().size());
            assertEquals(3, decoded.entries().get(0).chunkX());
            assertEquals(-5, decoded.entries().get(0).chunkZ());
            assertEquals(1234567890123L, decoded.entries().get(0).hash());
            assertEquals(0L, decoded.entries().get(1).hash(), "hash=0 表示未知，必须保真");
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("快照包编解码：snapshot=true 且批量上限内")
    void roundTripSnapshot() {
        List<ChunkAuthorityS2CPacket.Entry> entries = new ArrayList<>();
        for (int i = 0; i < ChunkAuthorityS2CPacket.MAX_ENTRIES; i++) {
            entries.add(new ChunkAuthorityS2CPacket.Entry(i, -i, i + 1L));
        }
        ChunkAuthorityS2CPacket original =
                new ChunkAuthorityS2CPacket("minecraft:overworld", 1L, true, entries);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(buf);
            ChunkAuthorityS2CPacket decoded = ChunkAuthorityS2CPacket.decode(buf);
            assertTrue(decoded.snapshot());
            assertEquals(ChunkAuthorityS2CPacket.MAX_ENTRIES, decoded.entries().size());
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("超出批量上限直接构造失败（防解码侧分配爆炸）")
    void rejectsOversizedBatch() {
        List<ChunkAuthorityS2CPacket.Entry> entries = new ArrayList<>();
        for (int i = 0; i <= ChunkAuthorityS2CPacket.MAX_ENTRIES; i++) {
            entries.add(new ChunkAuthorityS2CPacket.Entry(i, 0, 1L));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkAuthorityS2CPacket("minecraft:overworld", 0L, false, entries));
    }

    @Test
    @DisplayName("enter 单柱工厂默认非快照")
    void enterFactoryIsNotSnapshot() {
        ChunkAuthorityS2CPacket packet =
                ChunkAuthorityS2CPacket.enter("minecraft:overworld", 3L, 7, 8, 42L);
        assertFalse(packet.snapshot());
        assertEquals(1, packet.entries().size());
        assertEquals(42L, packet.entries().get(0).hash());
    }

    @Test
    @DisplayName("权威 hash 缓存：逐段 put/get 命中、未命中返回 null、方块变更失效、null 不入表")
    void hashCacheHitMissAndInvalidate() {
        ChunkAuthorityHashes.clear();
        ChunkAuthorityHashes.resetStats();
        ChunkPos pos = new ChunkPos(11, -7);
        assertNull(ChunkAuthorityHashes.getSections("minecraft:overworld", pos), "未写入应为未命中");

        long[] sections = {0x11L, 0L, 0x22L};
        long expected = ChunkContentHashUtil.combineSectionHashesFromArray(sections);
        ChunkAuthorityHashes.putSections("minecraft:overworld", pos, sections);
        long[] read = ChunkAuthorityHashes.getSections("minecraft:overworld", pos);
        assertEquals(3, read.length);
        assertEquals(0x11L, read[0]);
        assertEquals(0L, read[1]);
        assertEquals(0x22L, read[2]);
        assertEquals(expected, ChunkAuthorityHashes.get("minecraft:overworld", pos));
        assertEquals(expected, ChunkAuthorityHashes.peek("minecraft:overworld", 11, -7));

        // 防御性拷贝：外部改动源数组不得污染缓存
        sections[0] = 0x33L;
        assertEquals(0x11L, ChunkAuthorityHashes.getSections("minecraft:overworld", pos)[0]);

        // 跨维同坐标互不覆盖
        assertNull(ChunkAuthorityHashes.getSections("minecraft:the_nether", pos));

        ChunkAuthorityHashes.invalidate("minecraft:overworld", pos);
        assertNull(ChunkAuthorityHashes.getSections("minecraft:overworld", pos), "失效后必须未命中");
        assertEquals(1L, ChunkAuthorityHashes.invalidationCount());

        // null 视为未知，不入表
        ChunkAuthorityHashes.putSections("minecraft:overworld", pos, null);
        assertNull(ChunkAuthorityHashes.getSections("minecraft:overworld", pos));

        // 长度 0（全空气柱）是合法条目：柱级 hash 由 combineSectionHashesFromArray 给 1
        ChunkAuthorityHashes.putSections("minecraft:overworld", pos, new long[0]);
        assertEquals(1L, ChunkAuthorityHashes.get("minecraft:overworld", pos));

        ChunkAuthorityHashes.clear();
        assertEquals(0, ChunkAuthorityHashes.size());
    }
}
