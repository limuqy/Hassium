package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.compression.CompressionService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * type 126 区块槽编解码：{@code [length(4)][type=126][magic 0x48][hash(8)][ZSTD]}。
 * <p>
 * 从 {@code MixinRegionFile} 抽出，供 Mixin 与 {@link ShadowStorageManager} 共用。
 * magic 0x48 与 ZSTD 帧头 0x28 不冲突，可在不解压的情况下探 hash。
 */
public final class HassiumType126Codec {

    public static final byte COMPRESSION_TYPE = (byte) 126;
    public static final byte HASH_MAGIC = (byte) 0x48;
    public static final int HASH_LENGTH = 8;

    private HassiumType126Codec() {}

    /**
     * 压缩 NBT 并包装为原版 region 槽字节（含 4B length + 1B type）。
     *
     * @param contentHash 非 null 时写入 0x48 + 8B hash；null 时保持旧 126 格式
     */
    public static byte[] encodeSector(byte[] rawNbt, Long contentHash, int zstdLevel) throws IOException {
        byte[] compressed;
        try {
            compressed = CompressionService.getInstance().compressWithDictionary(rawNbt, zstdLevel);
        } catch (Exception e) {
            throw new IOException("Hassium compression failed", e);
        }
        int payloadLength = 1 + compressed.length;
        ByteBuffer sector;
        if (contentHash != null) {
            payloadLength += 1 + HASH_LENGTH;
            sector = ByteBuffer.allocate(4 + payloadLength);
            sector.putInt(payloadLength);
            sector.put(COMPRESSION_TYPE);
            sector.put(HASH_MAGIC);
            sector.putLong(contentHash);
            sector.put(compressed);
        } else {
            sector = ByteBuffer.allocate(4 + payloadLength);
            sector.putInt(payloadLength);
            sector.put(COMPRESSION_TYPE);
            sector.put(compressed);
        }
        return sector.array();
    }

    /**
     * 解 type 126 槽的 type 之后载荷（Mixin 读路径的 {@code rawData}）。
     * 不解探活请用 {@link #probeHash(byte[])}。
     */
    public static Decoded decode(byte[] rawAfterType) throws IOException {
        byte[] compressed = rawAfterType;
        Long hash = probeHash(rawAfterType);
        if (hash != null) {
            compressed = Arrays.copyOfRange(rawAfterType, 1 + HASH_LENGTH, rawAfterType.length);
        }
        byte[] nbt;
        try {
            nbt = CompressionService.getInstance().decompressWithDictionary(compressed);
        } catch (Exception e) {
            throw new IOException("Hassium decompression failed", e);
        }
        return new Decoded(nbt, hash);
    }

    /**
     * 从 type 之后载荷读 8B hash，不解压。无 0x48 头返回 null。
     */
    public static Long probeHash(byte[] rawAfterType) {
        if (rawAfterType == null || rawAfterType.length < 1 + HASH_LENGTH) {
            return null;
        }
        if (rawAfterType[0] != HASH_MAGIC) {
            return null;
        }
        long storedHash = 0L;
        for (int i = 0; i < HASH_LENGTH; i++) {
            storedHash = (storedHash << 8) | (rawAfterType[i + 1] & 0xFFL);
        }
        return storedHash;
    }

    /** type 之后的压缩载荷（strip 4B length + 1B type）。 */
    public static byte[] payloadAfterType(byte[] sector) {
        if (sector == null || sector.length < 5) {
            return new byte[0];
        }
        int length = ByteBuffer.wrap(sector, 0, 4).getInt();
        if (length <= 1 || 5 + (length - 1) > sector.length) {
            int dataLen = Math.max(0, sector.length - 5);
            byte[] raw = new byte[dataLen];
            System.arraycopy(sector, 5, raw, 0, dataLen);
            return raw;
        }
        byte[] raw = new byte[length - 1];
        System.arraycopy(sector, 5, raw, 0, raw.length);
        return raw;
    }

    public record Decoded(byte[] nbt, Long contentHash) {}
}
