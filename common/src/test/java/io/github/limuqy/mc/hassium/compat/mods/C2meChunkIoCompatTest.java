package io.github.limuqy.mc.hassium.compat.mods;

import io.github.limuqy.mc.hassium.storage.HassiumType126Codec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2ME 写侧接管地基单测。
 * <p>
 * 刻意**不触碰任何 MC 类**（不用 ChunkPos / ShadowStorageHashes / Bootstrap）：
 * 1.21.x 的 {@code Bootstrap.bootStrap()} 在纯 JUnit 环境下自身会失败
 * （{@code FireBlock.bootStrap} → {@code Blocks.<clinit>} → {@code Util.doFetchChoiceType}），
 * 因此补丁逻辑被抽成 MC 无关的
 * {@link C2meChunkIoCompat#patchSectorBuffer} 核心，{@link C2meChunkIoCompat#patchSector}
 * 只做坐标/hash 取值的薄适配。
 * <p>
 * 覆盖两件容易静默出错的事：
 * <ol>
 *   <li>{@link HassiumPayloadStream} 写在 type 字节之后的载荷布局必须与
 *       {@link HassiumType126Codec} 的 {@code [0x48][hash 8B][ZSTD]} 约定一致
 *       （length = 载荷 + 1 的约定由调用方回填，本流不写 length/type）；</li>
 *   <li>签名检测必须只命中本兼容层产出的槽，对 vanilla 压缩槽 / 已完成槽 /
 *       越界缓冲都是 no-op（幂等、零重压）。</li>
 * </ol>
 */
class C2meChunkIoCompatTest {

    private static final byte[] NBT_SAMPLE = new byte[]{
            0x0A, 0x00, 0x00, 0x01, 0x00, 0x04, 'D', 'a', 't', 'a', 0x0A, 0x00
    };

    private static final long HASH = 0x0123456789ABCDEFL;

    /** 只需字典压缩初始化；MC 引导在此环境不可用且本测试不需要。 */
    @BeforeAll
    static void initCompression() {
        io.github.limuqy.mc.hassium.compression.HassiumCompression.reset();
        io.github.limuqy.mc.hassium.compression.HassiumCompression.initialize();
    }

    @Test
    void payloadStreamWritesMagicHashPlaceholderThenZstd() throws Exception {
        ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        try (OutputStream out = new HassiumPayloadStream(delegate, 1)) {
            out.write(NBT_SAMPLE);
        }
        byte[] payload = delegate.toByteArray();

        assertTrue(payload.length > 1 + HassiumType126Codec.HASH_LENGTH,
                "载荷应包含 magic + hash 占位 + 压缩体");
        assertEquals(HassiumType126Codec.HASH_MAGIC, payload[0], "载荷首字节必须是 hash magic");
        assertArrayEquals(new byte[HassiumType126Codec.HASH_LENGTH],
                Arrays.copyOfRange(payload, 1, 1 + HassiumType126Codec.HASH_LENGTH),
                "hash 必须是全零占位，待 RegionFile.write 按坐标回填");
        // ZSTD 帧魔数首字节 0x28（与 magic 0x48 不冲突，故可在不解压的情况下探 hash）
        assertEquals((byte) 0x28, payload[1 + HassiumType126Codec.HASH_LENGTH],
                "hash 之后应是 ZSTD 帧");
    }

    @Test
    void patchSectorBufferConvertsForeignSectorAndFillsHash() throws Exception {
        ByteBuffer buffer = foreignSectorBuffer();

        assertTrue(C2meChunkIoCompat.patchSectorBuffer(buffer, HASH, true));

        assertEquals(HassiumType126Codec.COMPRESSION_TYPE, buffer.get(4), "type 字节应被改写为 126");
        assertEquals(HASH, buffer.getLong(6), "hash 占位应按坐标回填");
        // 载荷本体不被触碰：magic 与压缩体保持原样（零重压）
        assertEquals(HassiumType126Codec.HASH_MAGIC, buffer.get(5));
        assertEquals((byte) 0x28, buffer.get(5 + 1 + HassiumType126Codec.HASH_LENGTH));
    }

    @Test
    void patchSectorBufferKeepsZeroPlaceholderWhenHashUnknown() throws Exception {
        ByteBuffer buffer = foreignSectorBuffer();

        assertTrue(C2meChunkIoCompat.patchSectorBuffer(buffer, 0L, false));

        assertEquals(HassiumType126Codec.COMPRESSION_TYPE, buffer.get(4), "type 仍应改写");
        assertEquals(0L, buffer.getLong(6), "无 hash 时保留全零占位（0 = 无基线，按缺失处理）");
    }

    @Test
    void patchSectorBufferLeavesVanillaZlibSectorUntouched() {
        // 原版 zlib 槽：[len][type=2][0x78 0x9C ...]
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.putInt(0, 6);
        buffer.put(4, (byte) 2);
        buffer.put(5, (byte) 0x78);
        buffer.put(6, (byte) 0x9C);

        assertFalse(C2meChunkIoCompat.patchSectorBuffer(buffer, HASH, true));

        assertEquals((byte) 2, buffer.get(4), "vanilla 槽不得被改写");
        assertEquals((byte) 0x78, buffer.get(5));
        assertEquals((byte) 0x9C, buffer.get(6), "vanilla 槽的载荷区不得被写入");
        assertEquals((byte) 0, buffer.get(7), "hash 区之外不得被触碰");
    }

    @Test
    void patchSectorBufferIsIdempotentForCompletedHassiumSector() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.putInt(0, 6);
        buffer.put(4, HassiumType126Codec.COMPRESSION_TYPE);
        buffer.put(5, HassiumType126Codec.HASH_MAGIC);
        buffer.putLong(6, HASH);

        assertFalse(C2meChunkIoCompat.patchSectorBuffer(buffer, 0xFFFFL, true));

        assertEquals(HassiumType126Codec.COMPRESSION_TYPE, buffer.get(4));
        assertEquals(HASH, buffer.getLong(6), "已完成槽的 hash 不得被覆盖");
    }

    @Test
    void patchSectorBufferToleratesShortAndNullBuffers() {
        assertFalse(C2meChunkIoCompat.patchSectorBuffer(null, HASH, true));
        assertFalse(C2meChunkIoCompat.patchSectorBuffer(ByteBuffer.allocate(4), HASH, true));
        // 恰好小于 [4B len + 1B type + 1B magic + 8B hash]
        assertFalse(C2meChunkIoCompat.patchSectorBuffer(ByteBuffer.allocate(13), HASH, true));
    }

    @Test
    void foreignLightEngineAccessorsAreNullSafe() {
        // 未装 Starlight/ScalableLux 时 PROVIDER 为 null；null 引擎必须保守返回 vanilla 语义。
        assertFalse(ForeignLightEngine.isForeign(null));
        assertTrue(ForeignLightEngine.usesSectionDataClear(null));
        assertTrue(ForeignLightEngine.usesLightTaskWatermark(null));
        assertTrue(ForeignLightEngine.skySourcesReliable(null));
        assertFalse(ForeignLightEngine.mustForceFullRelight(null, true));
    }

    /** 构造 C2ME 语义的槽：{@code [len 4B][type=2][0x48][8B 占位][ZSTD]}（length = 载荷 + 1）。 */
    private static ByteBuffer foreignSectorBuffer() throws Exception {
        ByteArrayOutputStream payloadOut = new ByteArrayOutputStream();
        try (OutputStream out = new HassiumPayloadStream(payloadOut, 1)) {
            out.write(NBT_SAMPLE);
        }
        byte[] payload = payloadOut.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(5 + payload.length + 8);
        buffer.putInt(0, payload.length + 1);
        buffer.put(4, (byte) 2);
        buffer.position(5);
        buffer.put(payload);
        buffer.position(0);
        return buffer;
    }
}
