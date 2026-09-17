package io.github.limuqy.mc.hassium.compat.mods;

import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.shadow.storage.HassiumType126Codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 供外部区块 IO 实现（C2ME {@code ioSystem.replaceImpl}）使用的 type 126 载荷流。
 * <p>
 * 语义与 {@code MixinRegionFile} 写路径完全一致：调用方负责写 4B length 与 1B type，
 * 本流在其后写入 {@code [0x48][8B hash 占位][ZSTD(NBT)]}。
 * <p>
 * hash 与 type 字节由 {@link C2meChunkIoCompat#patchSector} 在
 * {@code RegionFile.write(ChunkPos, ByteBuffer)} 处按坐标回填——那里才有 {@code ChunkPos}，
 * 且该方法是所有写入者（vanilla IOWorker / C2ME）的唯一汇合点。
 * <p>
 * 载荷长度沿用原版约定（length 字段 = 载荷 + 1），因此调用方原有的
 * {@code count - 5 + 1} 回填逻辑无需改动。
 */
public final class HassiumPayloadStream extends ByteArrayOutputStream {

    /** 全零 hash 占位：由 {@code RegionFile.write} 补丁按坐标回填。 */
    private static final byte[] HASH_PLACEHOLDER = new byte[HassiumType126Codec.HASH_LENGTH];

    private final OutputStream delegate;
    private final int zstdLevel;
    private boolean closed;

    public HassiumPayloadStream(OutputStream delegate, int zstdLevel) {
        super(8192);
        this.delegate = delegate;
        this.zstdLevel = zstdLevel;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        byte[] nbt = this.toByteArray();
        super.close();
        byte[] compressed;
        try {
            compressed = CompressionService.getInstance().compressWithDictionary(nbt, zstdLevel);
        } catch (Exception e) {
            throw new IOException("Hassium compression failed", e);
        }
        delegate.write(HassiumType126Codec.HASH_MAGIC);
        delegate.write(HASH_PLACEHOLDER);
        delegate.write(compressed);
        delegate.flush();
    }
}
