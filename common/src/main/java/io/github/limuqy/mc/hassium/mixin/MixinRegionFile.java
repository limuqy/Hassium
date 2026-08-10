package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.storage.HassiumChunkWriteBuffer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    /** hash payload magic（ZSTD 首字节恒 0x28，不会撞 0x48——判别安全）。 */
    @Unique
    private static final byte HASSIUM_HASH_MAGIC = (byte) 0x48;

    @Unique
    private static final int HASSIUM_HASH_LENGTH = 8;

    @Unique
    private static final int SECTOR_SIZE = 4096;

    /**
     * 原版 timestamp 扇区：1024 × int32（不可扩成 8B contentHash，否则会侵占 data sector）。
     * 服务端存档仍写 unix 秒；contentHash 走 payload 头（magic 0x48 + 8B hash），
     * 由存储桥 {@code ShadowStorageHashes} 提供。
     */
    @Unique
    private ByteBuffer hassium$vanillaTimestamps;

    @Unique
    private boolean hassium$timestampsLoaded = false;

    /** 时间戳表有未落盘修改（攒批：close/flush 时一次性写回，对齐原版 header 攒批语义）。 */
    @Unique
    private boolean hassium$metadataDirty = false;

    @Unique
    private boolean hassium$dedicatedServerContext = false;

    @Unique
    private boolean hassium$serverTypeResolved = false;

    /**
     * 存储格式只服务专用服务器：integrated server（单人/局域网）不写 Hassium type-126，
     * 存档保持原版格式。判定结果缓存——服务器类型在进程生命周期内不变。
     * 标志未写入（客户端上下文 / 启动早期）时保守走原版，绝不改写存档。
     * 读路径不做此判定：旧 Hassium 区块仍需兼容读取。
     */
    @Unique
    private boolean hassium$isDedicatedServerContext() {
        if (!hassium$serverTypeResolved) {
            hassium$serverTypeResolved = true;
            hassium$dedicatedServerContext =
                    io.github.limuqy.mc.hassium.server.RuntimeServerContext.isDedicatedServerContext();
        }
        return hassium$dedicatedServerContext;
    }

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
        // review-fix: T7-64: 脏标记——不再每次 chunk 写立即整扇区落盘
        hassium$metadataDirty = true;
    }

    @Unique
    public void hassium$flushMetadata() {
        // review-fix: T7-64: 攒批——无脏修改时零 I/O
        if (!hassium$metadataDirty) {
            return;
        }
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
                hassium$metadataDirty = false;
            }
        } catch (IOException e) {
            hassium$LOGGER.error("Hassium: Failed to flush vanilla timestamp sector", e);
        }
    }

    // review-fix: T7-64: 脏时间戳扇区在 close（停机/逐出）与 flush（周期性存档）时一次性写回，
    // 取代原每次 chunk 写都整 16KB 扇区同步落盘（批量保存时写放大明显）
    @Inject(method = "close", at = @At("HEAD"))
    private void hassium$onClose(CallbackInfo ci) {
        hassium$flushMetadata();
    }

    @Inject(method = "flush", at = @At("HEAD"))
    private void hassium$onFlush(CallbackInfo ci) {
        hassium$flushMetadata();
    }

    @Inject(method = "getChunkDataInputStream", at = @At("HEAD"), cancellable = true)
    private void hassium$onGetChunkDataInputStream(ChunkPos pos, CallbackInfoReturnable<DataInputStream> cir) {
        HassiumConfigService configService = HassiumConfigService.getInstance();
        if (!configService.isStorageEnabled()
                && !io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
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
        boolean shadow = io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext();
        hassium$LOGGER.debug("Hassium: RegionFile write gate pos={} storageEnabled={} dedicated={} shadow={}",
                pos, configService.isStorageEnabled(), hassium$isDedicatedServerContext(), shadow);
        if (!configService.isStorageEnabled() && !shadow) {
            return;
        }

        // 单人/局域网（integrated server）不写 Hassium 格式，放行原版输出流；
        // 影子服务端（客户端进程内世界管理后端）固定写 Hassium 格式（type 126）。
        if (!hassium$isDedicatedServerContext() && !shadow) {
            return;
        }

        // storage.mode 键已删（REQ 决策 2/B）：存储模式内部固定 mirror，
        // readonly_vanilla 只读放行分支不再可达。

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

        byte[] rawData = new byte[dataBuf.remaining()];
        dataBuf.get(rawData);

        // hash payload：magic 判别（ZSTD 首字节恒 0x28，不会撞 0x48）。
        // 新格式 [magic(1)][hash(8)][zstd]：解压 zstd 并回填 hash 表（R2 比对用）。
        byte[] compressedData = rawData;
        if (rawData.length >= HASSIUM_HASH_LENGTH + 1 && rawData[0] == HASSIUM_HASH_MAGIC) {
            long storedHash = 0L;
            for (int i = 0; i < HASSIUM_HASH_LENGTH; i++) {
                storedHash = (storedHash << 8) | (rawData[i + 1] & 0xFFL);
            }
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(pos.x, pos.z, storedHash);
            compressedData = new byte[rawData.length - HASSIUM_HASH_LENGTH - 1];
            System.arraycopy(rawData, HASSIUM_HASH_LENGTH + 1, compressedData, 0, compressedData.length);
        }

        // 使用 ZSTD 字典解压
        byte[] decompressed;
        try {
            decompressed = CompressionService.getInstance().decompressWithDictionary(compressedData);
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
            compressedData = CompressionService.getInstance().compressWithDictionary(rawNbtData, level);
        } catch (Exception e) {
            hassium$LOGGER.error("ZSTD dictionary compression failed for chunk {}, falling back to vanilla", pos, e);
            if (configService.isAutoDowngradeEnabled()) {
                hassium$writeVanillaFallback(pos, rawNbtData);
                return;
            }
            throw new IOException("Hassium compression failed", e);
        }

        // 构造 payload: [length(4)][compressionType(1)][magic(1)][hash(8)][compressedData]
        // （hash 表有值才带 magic+hash；无值保持旧 126 格式，兼容）
        Long storedHash = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(pos);
        int payloadLength = 1 + compressedData.length;
        ByteBuffer sectorBuf;
        if (storedHash != null) {
            payloadLength += 1 + HASSIUM_HASH_LENGTH;
            sectorBuf = ByteBuffer.allocate(4 + payloadLength);
            sectorBuf.putInt(payloadLength);
            sectorBuf.put(HASSIUM_COMPRESSION_TYPE);
            sectorBuf.put(HASSIUM_HASH_MAGIC);
            sectorBuf.putLong(storedHash);
            sectorBuf.put(compressedData);
        } else {
            sectorBuf = ByteBuffer.allocate(4 + payloadLength);
            sectorBuf.putInt(payloadLength);
            sectorBuf.put(HASSIUM_COMPRESSION_TYPE);
            sectorBuf.put(compressedData);
        }
        sectorBuf.flip();

        hassium$self().invokeWrite(pos, sectorBuf);

        // 更新时间戳
        // review-fix: T7-64: 不再每块立即 flushMetadata——脏标记攒批，close/flush 时统一写回
        int timestamp = (int) (System.currentTimeMillis() / 1000);
        hassium$setChunkTimestamp(pos, timestamp);
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
}
