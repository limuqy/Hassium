package io.github.limuqy.mc.hassium.mixin;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.github.limuqy.mc.hassium.compression.CompressionAlgorithmId;
import io.github.limuqy.mc.hassium.compression.CompressionException;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.compression.DictionaryRegistry;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.storage.HassiumChunkWriteBuffer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * RegionFile Mixin - 拦截区块读写，实现 ZSTD 字典压缩。
 * <p>
 * 读取：检测压缩类型 126，使用 ZSTD 字典解压返回原版 NBT 流。
 * 写入：将原始 NBT 使用 ZSTD 字典压缩，使用类型 126 写入 sector。
 * <p>
 * 格式与原版完全一致，只替换压缩算法：
 * - 原版：Length + CompressionType(1/2/3) + CompressedData
 * - Hassium：Length + CompressionType(126) + ZstdDictCompressedData
 */
@Mixin(RegionFile.class)
public abstract class MixinRegionFile {

    @Unique
    private static final Logger hassium$LOGGER = LoggerFactory.getLogger("Hassium/RegionFile");

    @Unique
    private static final byte HASSIUM_COMPRESSION_TYPE = (byte) 126;

    @Unique
    private static final int SECTOR_SIZE = 4096;

    /**
     * 原版 timestamp 扇区：1024 × int32（不可扩成 8B contentHash，否则会侵占 data sector）。
     * 服务端存档仍写 unix 秒；客户端缓存的 contentHash 由 HassiumRegionFile 独立管理。
     */
    @Unique
    private ByteBuffer hassium$vanillaTimestamps;

    @Unique
    private boolean hassium$timestampsLoaded = false;

    @Unique
    private RegionFileAccessor hassium$self() {
        return (RegionFileAccessor) (Object) this;
    }

    @Unique
    private ByteBuffer hassium$getVanillaTimestamps() {
        if (!hassium$timestampsLoaded) {
            hassium$timestampsLoaded = true;
            hassium$vanillaTimestamps = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.BIG_ENDIAN);
            try {
                FileChannel channel = hassium$self().getFileChannel();
                if (channel != null && channel.size() > SECTOR_SIZE) {
                    channel.read(hassium$vanillaTimestamps, SECTOR_SIZE);
                    hassium$vanillaTimestamps.clear();
                }
            } catch (IOException e) {
                hassium$LOGGER.debug("Hassium: No vanilla timestamp sector yet");
            }
        }
        return hassium$vanillaTimestamps;
    }

    @Unique
    public void hassium$setChunkTimestamp(ChunkPos pos, int timestamp) {
        ByteBuffer table = hassium$getVanillaTimestamps();
        if (table == null) {
            return;
        }
        int index = (pos.x & 31) + (pos.z & 31) * 32;
        table.putInt(index * 4, timestamp);
    }

    @Unique
    public void hassium$flushMetadata() {
        ByteBuffer table = hassium$getVanillaTimestamps();
        if (table == null) {
            return;
        }
        try {
            FileChannel channel = hassium$self().getFileChannel();
            if (channel != null) {
                table.position(0);
                table.limit(SECTOR_SIZE);
                channel.write(table, SECTOR_SIZE);
                table.clear();
            }
        } catch (IOException e) {
            hassium$LOGGER.error("Hassium: Failed to flush vanilla timestamp sector", e);
        }
    }

    @Inject(method = "getChunkDataInputStream", at = @At("HEAD"), cancellable = true)
    private void hassium$onGetChunkDataInputStream(ChunkPos pos, CallbackInfoReturnable<DataInputStream> cir) {
        HassiumConfigService configService = HassiumConfigService.getInstance();
        if (!configService.isStorageEnabled()) {
            return;
        }

        try {
            DataInputStream result = hassium$tryReadHassiumChunk(pos);
            if (result != null) {
                cir.setReturnValue(result);
            }
        } catch (Exception e) {
            hassium$LOGGER.error("Failed to read Hassium chunk at {}, falling back to vanilla", pos, e);
        }
    }

    @Inject(method = "getChunkDataOutputStream", at = @At("HEAD"), cancellable = true)
    private void hassium$onGetChunkDataOutputStream(ChunkPos pos, CallbackInfoReturnable<DataOutputStream> cir) {
        HassiumConfigService configService = HassiumConfigService.getInstance();
        if (!configService.isStorageEnabled()) {
            return;
        }

        String mode = configService.getConfig().storage().mode();
        if ("readonly_vanilla".equals(mode)) {
            return;
        }

        HassiumChunkWriteBuffer buffer = new HassiumChunkWriteBuffer(
                data -> hassium$writeHassiumPayload(pos, data)
        );
        cir.setReturnValue(new DataOutputStream(new BufferedOutputStream(buffer)));
    }

    @Unique
    @Nullable
    private DataInputStream hassium$tryReadHassiumChunk(ChunkPos pos) throws IOException {
        int offset = hassium$self().invokeGetOffset(pos);
        if (offset == 0) {
            return null;
        }

        int sectorNumber = offset >> 8;
        int numSectors = offset & 0xFF;
        if (numSectors == 0) {
            return null;
        }

        long fileOffset = (long) sectorNumber * 4096;
        FileChannel channel = hassium$self().getFileChannel();

        ByteBuffer headerBuf = ByteBuffer.allocate(5);
        synchronized (channel) {
            channel.read(headerBuf, fileOffset);
        }
        headerBuf.flip();

        if (headerBuf.remaining() < 5) {
            return null;
        }

        int length = headerBuf.getInt();
        byte compressionType = headerBuf.get();

        if (compressionType != HASSIUM_COMPRESSION_TYPE) {
            return null;
        }

        if (length <= 1) {
            return null;
        }

        int dataLength = length - 1;
        ByteBuffer dataBuf = ByteBuffer.allocate(dataLength);
        synchronized (channel) {
            channel.read(dataBuf, fileOffset + 5);
        }
        dataBuf.flip();

        byte[] compressedData = new byte[dataBuf.remaining()];
        dataBuf.get(compressedData);

        // 使用 ZSTD 字典解压
        byte[] decompressed;
        try {
            decompressed = hassium$decompressWithDictionary(compressedData);
        } catch (Exception e) {
            hassium$LOGGER.error("ZSTD dictionary decompression failed for chunk {}", pos, e);
            if (HassiumConfigService.getInstance().isAutoDowngradeEnabled()) {
                return null;
            }
            throw new IOException("Hassium decompression failed at " + pos, e);
        }

        hassium$LOGGER.debug("Read Hassium chunk {}: {} -> {} bytes",
                pos, compressedData.length, decompressed.length);

        return new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(decompressed)));
    }

    @Unique
    private void hassium$writeHassiumPayload(ChunkPos pos, byte[] rawNbtData) throws IOException {
        HassiumConfigService configService = HassiumConfigService.getInstance();
        int level = configService.getStorageCompressionLevel();

        // 使用 ZSTD 字典压缩
        byte[] compressedData;
        try {
            compressedData = hassium$compressWithDictionary(rawNbtData, level);
        } catch (Exception e) {
            hassium$LOGGER.error("ZSTD dictionary compression failed for chunk {}, falling back to vanilla", pos, e);
            if (configService.isAutoDowngradeEnabled()) {
                hassium$writeVanillaFallback(pos, rawNbtData);
                return;
            }
            throw new IOException("Hassium compression failed", e);
        }

        // 构造 payload: [length(4)][compressionType(1)][compressedData]
        int payloadLength = 1 + compressedData.length;
        ByteBuffer sectorBuf = ByteBuffer.allocate(4 + payloadLength);
        sectorBuf.putInt(payloadLength);
        sectorBuf.put(HASSIUM_COMPRESSION_TYPE);
        sectorBuf.put(compressedData);
        sectorBuf.flip();

        hassium$self().invokeWrite(pos, sectorBuf);

        // 更新时间戳
        int timestamp = (int) (System.currentTimeMillis() / 1000);
        hassium$setChunkTimestamp(pos, timestamp);
        hassium$flushMetadata();

        hassium$LOGGER.debug("Wrote Hassium chunk {}: {} -> {} bytes",
                pos, rawNbtData.length, compressedData.length);
    }

    @Unique
    private void hassium$writeVanillaFallback(ChunkPos pos, byte[] rawNbtData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(rawNbtData.length);
        try (java.util.zip.DeflaterOutputStream deflater =
                     new java.util.zip.DeflaterOutputStream(baos)) {
            deflater.write(rawNbtData);
        }
        byte[] compressed = baos.toByteArray();

        int payloadLength = 1 + compressed.length;
        ByteBuffer sectorBuf = ByteBuffer.allocate(4 + payloadLength);
        sectorBuf.putInt(payloadLength);
        sectorBuf.put((byte) 2); // Zlib
        sectorBuf.put(compressed);
        sectorBuf.flip();

        hassium$self().invokeWrite(pos, sectorBuf);
    }

    /**
     * 使用 ZSTD 字典压缩数据
     */
    @Unique
    private byte[] hassium$compressWithDictionary(byte[] data, int level) throws CompressionException {
        CompressionService compressionService = CompressionService.getInstance();
        DictionaryRegistry registry = compressionService.getDictionaryRegistry()
                .orElseThrow(() -> new CompressionException("Dictionary registry not available"));

        String dictionaryId = HassiumConfigService.getInstance().getConfig().storage().zstdDictionaryId();
        byte[] dictionary = registry.findDictionary(dictionaryId)
                .orElseThrow(() -> new CompressionException("Dictionary not found: " + dictionaryId));

        ZstdDictCompress dict = new ZstdDictCompress(dictionary, level);
        return Zstd.compress(data, dict);
    }

    /**
     * 使用 ZSTD 字典解压数据
     */
    @Unique
    private byte[] hassium$decompressWithDictionary(byte[] compressedData) throws CompressionException {
        CompressionService compressionService = CompressionService.getInstance();
        DictionaryRegistry registry = compressionService.getDictionaryRegistry()
                .orElseThrow(() -> new CompressionException("Dictionary registry not available"));

        String dictionaryId = HassiumConfigService.getInstance().getConfig().storage().zstdDictionaryId();
        byte[] dictionary = registry.findDictionary(dictionaryId)
                .orElseThrow(() -> new CompressionException("Dictionary not found: " + dictionaryId));

        ZstdDictDecompress dict = new ZstdDictDecompress(dictionary);
        // 使用推荐的解压方法：decompressFastDict
        int decompressedSize = (int) Zstd.decompressedSize(compressedData);
        if (decompressedSize <= 0) {
            decompressedSize = compressedData.length * 4; // 估算值
        }
        byte[] result = new byte[decompressedSize];
        long actualSize = Zstd.decompressFastDict(result, 0, compressedData, 0, compressedData.length, dict);
        if (actualSize <= 0) {
            throw new CompressionException.DecompressionFailedException("ZSTD dictionary decompression failed: invalid output");
        }
        // 如果实际大小与预估不同，截取实际大小
        if (actualSize < decompressedSize) {
            byte[] trimmed = new byte[(int) actualSize];
            System.arraycopy(result, 0, trimmed, 0, (int) actualSize);
            return trimmed;
        }
        return result;
    }
}
