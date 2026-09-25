package io.github.limuqy.mc.hassium.protocol;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字典协议面单测：快照 hash、dictionary_sync / aggregation_ready 的向后兼容编解码、
 * 尾随字节拒绝、DictionaryManager 客户端安装与 epoch 查找。
 */
class DictionaryProtocolTest {

    // ===== DictionarySnapshot =====

    @Test
    void fnv1a64KnownVectors() {
        // FNV-1a 64 标准测试向量（跨 JVM 确定性的锚点）
        assertEquals(0xcbf29ce484222325L, DictionarySnapshot.hash(new byte[0]));
        assertEquals(0xaf63dc4c8601ec8cL, DictionarySnapshot.hash(new byte[]{'a'}));
        assertEquals(0x85944171f73967e8L,
                DictionarySnapshot.hash("foobar".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void snapshotOfEmptyIsUninstalledMarker() {
        DictionarySnapshot empty = DictionarySnapshot.of(3, new byte[0]);
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.epoch());
        assertEquals(0L, empty.id());
        assertTrue(empty.matches(0, 0L));
        assertFalse(empty.matches(3, 0L));
    }

    @Test
    void snapshotDefensiveCopy() {
        byte[] dict = "dict".getBytes(StandardCharsets.UTF_8);
        DictionarySnapshot snapshot = DictionarySnapshot.of(1, dict);
        dict[0] = 'X';
        assertEquals('d', snapshot.data()[0]);
    }

    // ===== DictionarySyncPayload =====

    @Test
    void dictionarySyncRoundTripExtended() {
        byte[] dict = "aggregation-dictionary-bytes".getBytes(StandardCharsets.UTF_8);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new DictionarySyncPayload(dict, false, 3, DictionarySnapshot.hash(dict), true).encode(buf);
            DictionarySyncPayload out = DictionarySyncPayload.decode(buf);
            assertTrue(out.hasDictionaryInfo());
            assertEquals(3, out.dictionaryEpoch());
            assertEquals(DictionarySnapshot.hash(dict), out.dictionaryId());
            assertArrayEquals(dict, out.dictionary());
            assertFalse(out.isChunkDict());
        } finally {
            buf.release();
        }
    }

    @Test
    void dictionarySyncLegacyBytesDecodeWithoutExtension() {
        // 旧服务端线格式：[bool][varint len][bytes]，无尾部扩展
        byte[] dict = "legacy".getBytes(StandardCharsets.UTF_8);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeBoolean(false);
            buf.writeVarInt(dict.length);
            buf.writeBytes(dict);
            DictionarySyncPayload out = DictionarySyncPayload.decode(buf);
            assertFalse(out.hasDictionaryInfo());
            assertEquals(0, out.dictionaryEpoch());
            assertEquals(0L, out.dictionaryId());
            assertArrayEquals(dict, out.dictionary());
        } finally {
            buf.release();
        }
    }

    @Test
    void dictionarySyncTrailingGarbageRejected() {
        byte[] dict = "d".getBytes(StandardCharsets.UTF_8);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeBoolean(false);
            buf.writeVarInt(dict.length);
            buf.writeBytes(dict);
            buf.writeVarInt(1);
            buf.writeLong(2L);
            buf.writeByte(0x7F); // 扩展字段后的尾随垃圾
            assertThrows(IllegalArgumentException.class, () -> DictionarySyncPayload.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void dictionarySyncOversizedLengthRejected() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeBoolean(false);
            buf.writeVarInt(256 * 1024 + 1);
            assertThrows(IllegalArgumentException.class, () -> DictionarySyncPayload.decode(buf));
        } finally {
            buf.release();
        }
    }

    // ===== AggregationReadyPayload =====

    @Test
    void aggregationReadyRoundTripWithDictionaryInfo() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new AggregationReadyPayload(true, 2, 0xAABBCCDDEEFL).encode(buf);
            AggregationReadyPayload out = AggregationReadyPayload.decode(buf);
            assertTrue(out.isReady());
            assertTrue(out.hasDictionaryInfo());
            assertEquals(2, out.getDictionaryEpoch());
            assertEquals(0xAABBCCDDEEFL, out.getDictionaryId());
        } finally {
            buf.release();
        }
    }

    @Test
    void aggregationReadyLegacyFormatHasNoDictionaryInfo() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeBoolean(true); // 旧客户端只写 boolean
            AggregationReadyPayload out = AggregationReadyPayload.decode(buf);
            assertTrue(out.isReady());
            assertFalse(out.hasDictionaryInfo());
            assertEquals(0, out.getDictionaryEpoch());
        } finally {
            buf.release();
        }
    }

    // ===== DictionaryManager 客户端安装 / epoch 查找 =====

    @Test
    void installKeepsPreviousForEpochLookup() {
        DictionaryManager.resetClientSession();
        try {
            byte[] d1 = "dict-v1".getBytes(StandardCharsets.UTF_8);
            byte[] d2 = "dict-v2".getBytes(StandardCharsets.UTF_8);
            DictionarySnapshot s1 = DictionarySnapshot.of(1, d1);
            DictionarySnapshot s2 = DictionarySnapshot.of(2, d2);

            DictionaryManager.installAggregationSnapshot(s1);
            assertSame(s1, DictionaryManager.getActiveSnapshot());
            assertArrayEquals(d1, DictionaryManager.getAggregationDictForEpoch(1));
            assertNull(DictionaryManager.getAggregationDictForEpoch(2));

            DictionaryManager.installAggregationSnapshot(s2);
            assertSame(s2, DictionaryManager.getActiveSnapshot());
            // 热切换窗口：旧 epoch 帧仍可按 previous 解码
            assertArrayEquals(d1, DictionaryManager.getAggregationDictForEpoch(1));
            assertArrayEquals(d2, DictionaryManager.getAggregationDictForEpoch(2));
            assertNull(DictionaryManager.getAggregationDictForEpoch(3));
        } finally {
            DictionaryManager.resetClientSession();
        }
    }

    @Test
    void installIsIdempotentForSameSnapshot() {
        DictionaryManager.resetClientSession();
        try {
            DictionarySnapshot s1 = DictionarySnapshot.of(1, "dict".getBytes(StandardCharsets.UTF_8));
            DictionaryManager.installAggregationSnapshot(s1);
            DictionaryManager.installAggregationSnapshot(DictionarySnapshot.of(1, "dict".getBytes(StandardCharsets.UTF_8)));
            // 同 epoch 同 id 重复下发幂等（不把 active 挤到 previous）
            assertSame(s1, DictionaryManager.getActiveSnapshot());
            assertNull(DictionaryManager.getAggregationDictForEpoch(0));
        } finally {
            DictionaryManager.resetClientSession();
        }
    }

    @Test
    void retainedSnapshotsCappedAtFiveIncludingActive() {
        DictionaryManager.resetClientSession();
        try {
            byte[][] expected = new byte[6][];
            for (int epoch = 1; epoch <= 6; epoch++) {
                expected[epoch - 1] = ("dict-v" + epoch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                DictionaryManager.installAggregationSnapshot(DictionarySnapshot.of(epoch, expected[epoch - 1]));
            }
            // 激活版 = 最新一份
            assertArrayEquals(expected[5], DictionaryManager.getAggregationDictForEpoch(6));
            // 保留历史 = 次新的 4 份（合计 5 份），最旧的 epoch 1 被挤出
            for (int epoch = 2; epoch <= 5; epoch++) {
                assertArrayEquals(expected[epoch - 1], DictionaryManager.getAggregationDictForEpoch(epoch));
            }
            assertNull(DictionaryManager.getAggregationDictForEpoch(1));
        } finally {
            DictionaryManager.resetClientSession();
        }
    }

    @Test
    void emptySyncClearsState() {
        DictionaryManager.resetClientSession();
        try {
            DictionaryManager.installAggregationSnapshot(
                    DictionarySnapshot.of(1, "dict".getBytes(StandardCharsets.UTF_8)));
            DictionaryManager.installAggregationSnapshot(DictionarySnapshot.of(0, new byte[0]));
            assertNull(DictionaryManager.getActiveSnapshot());
            assertNull(DictionaryManager.getAggregationDict());
        } finally {
            DictionaryManager.resetClientSession();
        }
    }
}
