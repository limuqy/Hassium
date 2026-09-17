package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.shadow.storage.HassiumChunkWriteBuffer;
import io.github.limuqy.mc.hassium.shadow.storage.HassiumType126Codec;
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
    private static final byte HASSIUM_COMPRESSION_TYPE = HassiumType126Codec.COMPRESSION_TYPE;

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

    /** 影子上下文：本 RegionFile 的 region 目录归属的存储管理器（懒解析一次，见 hassium$shadowStorage）。 */
    @Unique
    @Nullable
    private volatile io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager hassium$shadowStorage;

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
        boolean shadow = io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext();
        if (!configService.isStorageEnabled() && !shadow) {
            return;
        }

        try {
            DataInputStream result = hassium$tryReadHassiumChunk(pos);
            if (result != null) {
                cir.setReturnValue(result);
                return;
            }
            if (io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat
                    .shouldSkipVanillaChunkParse(shadow, false)) {
                // 非 126 / 槽空 / 解压失败：影子存档禁止原版当 zlib 解析（负长度风暴）。
                cir.setReturnValue(null);
            }
        } catch (Exception e) {
            hassium$LOGGER.error("Failed to read Hassium chunk at {}", pos, e);
            if (io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat
                    .shouldSkipVanillaChunkParse(shadow, false)) {
                cir.setReturnValue(null);
            }
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

        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager shadowMgr = hassium$shadowStorage();
        if (shadowMgr != null) {
            // 影子存档单写者 = 映像：原版写入收进映像，不碰 .mca（详见 adoptShadowPayload）。
            HassiumChunkWriteBuffer shadowBuffer = new HassiumChunkWriteBuffer(
                    data -> hassium$adoptShadowPayload(shadowMgr, pos, data)
            );
            cir.setReturnValue(new DataOutputStream(new BufferedOutputStream(shadowBuffer)));
            return;
        }

        HassiumChunkWriteBuffer buffer = new HassiumChunkWriteBuffer(
                data -> hassium$writeHassiumPayload(pos, data)
        );
        cir.setReturnValue(new DataOutputStream(new BufferedOutputStream(buffer)));
    }

    /**
     * 影子上下文：拦截 {@code RegionFile.write} —— 所有写入者（vanilla {@code ChunkBuffer}
     * 与外部 IO（C2ME {@code C2MEStorageThread}））的唯一汇合点，把 sector 收进映像后取消落盘。
     * <p>
     * 这是第二条缝：C2ME 的 {@code ioSystem.replaceImpl} 自拼 sector、绕过
     * {@code getChunkDataOutputStream}，{@link #hassium$onGetChunkDataOutputStream} 覆盖不到它
     * （见 {@code C2meChunkIoCompat}）。
     * <p>
     * 取消同时也短路挂在同一注入点的后续 HEAD 注入（Mixin 把 cancel 的返回插在本注入之后，
     * 冒烟实测：影子写 2363 次只放行了 21 次 C2ME 补丁）——影子上下文里 C2ME 的
     * type126/hash 补丁因此不再执行：本收编路径已自行归一化嵌入 hash（
     * {@link io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager#adoptEncodedColumn}），
     * {@link io.github.limuqy.mc.hassium.shadow.storage.RegionCache.Image#save} 落盘时写 type 字节，
     * 补丁在本上下文冗余。该补丁只在专用服存储路径（非影子）保留原有职责。
     */
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void hassium$adoptShadowWrite(ChunkPos pos, ByteBuffer buffer, CallbackInfo ci) {
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager mgr = hassium$shadowStorage();
        if (mgr == null) {
            return;
        }
        if (hassium$adoptShadowSector(mgr, pos, buffer)) {
            ci.cancel();
        }
    }

    /**
     * 影子上下文：本 RegionFile 归属的存储管理器（懒解析并缓存；未匹配返回 null）。
     * <p>
     * 一个 region 文件一个 RegionFile 实例、归属不变，可缓存；解析失败不缓存，
     * 等存储装配完成后的下次写入再试。
     */
    @Unique
    @Nullable
    private io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager hassium$shadowStorage() {
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager cached = hassium$shadowStorage;
        if (cached != null) {
            return cached;
        }
        if (!io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            return null;
        }
        io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer server =
                io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return null;
        }
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager resolved =
                server.storageForRegionDir(hassium$self().hassium$getExternalFileDir());
        if (resolved != null) {
            hassium$shadowStorage = resolved;
        }
        return resolved;
    }

    /**
     * 影子上下文：把原版 IOWorker 已序列化的柱收进 region 映像（不写 .mca）。
     * <p>
     * 原版 RegionFile 的 8KB 扇区表只在内存维护、写时整表回写磁盘且从不重读；映像落盘
     * 是整文件重写（按槽位重新紧凑扇区偏移）。若两边都写同一 .mca，偏移会互相错位——
     * 原版按旧偏移读回别的柱报 {@code wrong location; relocating}，或把半截载荷当
     * type126 解压失败。单写者 = 映像。
     */
    @Unique
    private void hassium$adoptShadowPayload(
            io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager mgr, ChunkPos pos, byte[] rawNbtData)
            throws IOException {
        try {
            int level = HassiumConfigService.getInstance().getStorageCompressionLevel();
            Long storedHash = io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes
                    .get(mgr.dimension(), pos);
            byte[] sector = HassiumType126Codec.encodeSector(rawNbtData, storedHash, level);
            if (!mgr.adoptEncodedColumn(pos, HassiumType126Codec.payloadAfterType(sector), storedHash)) {
                // 空载荷（编码产出异常）：回落原版写盘，不丢柱。
                hassium$writeHassiumPayload(pos, rawNbtData);
                return;
            }
            hassium$LOGGER.debug("Hassium: adopted shadow chunk {} ({} bytes) into region image",
                    pos, rawNbtData.length);
        } catch (Exception e) {
            // 编码失败不丢数据：回落原版写盘路径（罕见，且只为该柱牺牲单写者约束）。
            hassium$LOGGER.error("Hassium: shadow column adopt failed for {}, falling back", pos, e);
            hassium$writeHassiumPayload(pos, rawNbtData);
        }
    }

    /**
     * 影子上下文：从 {@code RegionFile.write} 的 sector 缓冲收进映像。
     *
     * @return true = 已收进映像（调用方取消落盘）；false = 非 Hassium 载荷或收编失败，回落原版写盘
     */
    @Unique
    private boolean hassium$adoptShadowSector(
            io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager mgr, ChunkPos pos, ByteBuffer buffer) {
        try {
            if (buffer == null || buffer.limit() < 5 + 1 + HassiumType126Codec.HASH_LENGTH) {
                return false;
            }
            if (buffer.get(5) != HassiumType126Codec.HASH_MAGIC) {
                // 非 Hassium 载荷（vanilla zlib/gzip/lz4/none 首字节均非 0x48）：不接管
                return false;
            }
            ByteBuffer view = buffer.duplicate();
            view.position(0);
            byte[] sector = new byte[view.limit()];
            view.get(sector);
            // 显式给坐标 hash：外部 IO 的嵌入 hash 是零占位，补丁与本收编无先后约束，
            // adoptEncodedColumn 会按坐标 hash 归一化嵌入头（见 normalizeEmbeddedHash）。
            Long hash = io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes
                    .get(mgr.dimension(), pos);
            boolean adopted = mgr.adoptEncodedColumn(pos, HassiumType126Codec.payloadAfterType(sector), hash);
            if (adopted) {
                hassium$LOGGER.debug("Hassium: adopted shadow sector {} ({} bytes) into region image",
                        pos, sector.length);
            }
            return adopted;
        } catch (Throwable t) {
            // 收编失败不丢数据：回落原版写盘（罕见，且只为该柱牺牲单写者约束）。
            hassium$LOGGER.error("Hassium: shadow sector adopt failed for {}, falling back", pos, t);
            return false;
        }
    }

    @Unique
    @Nullable
    private DataInputStream hassium$tryReadHassiumChunk(ChunkPos pos) throws IOException {
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager shadowMgr = hassium$shadowStorage();
        if (shadowMgr != null) {
            // 影子存档单写者 = 映像（整文件重写）。原版 RegionFile 的内存扇区表自构造起
            // 不再重读，早已与磁盘布局错位；读也必须走映像，否则按过期偏移读回别的柱
            // （wrong location / ZSTD 解压失败）。
            byte[] nbt = shadowMgr.readChunk(pos);
            if (nbt == null) {
                return null;
            }
            return new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(nbt)));
        }

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

        byte[] decompressed;
        try {
            HassiumType126Codec.Decoded decoded = HassiumType126Codec.decode(rawData);
            if (decoded.contentHash() != null) {
                io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.put(pos.x, pos.z, decoded.contentHash());
            }
            decompressed = decoded.nbt();
        } catch (Exception e) {
            hassium$LOGGER.error("ZSTD dictionary decompression failed for chunk {}", pos, e);
            if (HassiumConfigService.getInstance().isAutoDowngradeEnabled()) {
                return null;
            }
            throw new IOException("Hassium decompression failed at " + pos, e);
        }

        hassium$LOGGER.debug("Read Hassium chunk {}: payload {} -> {} bytes",
                pos, rawData.length, decompressed.length);

        return new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(decompressed)));
    }

    @Unique
    private void hassium$writeHassiumPayload(ChunkPos pos, byte[] rawNbtData) throws IOException {
        HassiumConfigService configService = HassiumConfigService.getInstance();
        int level = configService.getStorageCompressionLevel();

        Long storedHash = io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.get(pos);
        ByteBuffer sectorBuf;
        int compressedLength;
        try {
            byte[] sector = HassiumType126Codec.encodeSector(rawNbtData, storedHash, level);
            compressedLength = sector.length;
            sectorBuf = ByteBuffer.wrap(sector);
        } catch (Exception e) {
            hassium$LOGGER.error("ZSTD dictionary compression failed for chunk {}, falling back to vanilla", pos, e);
            if (configService.isAutoDowngradeEnabled()) {
                hassium$writeVanillaFallback(pos, rawNbtData);
                return;
            }
            throw new IOException("Hassium compression failed", e);
        }

        hassium$self().invokeWrite(pos, sectorBuf);

        // 更新时间戳
        // review-fix: T7-64: 不再每块立即 flushMetadata——脏标记攒批，close/flush 时统一写回
        int timestamp = (int) (System.currentTimeMillis() / 1000);
        hassium$setChunkTimestamp(pos, timestamp);
        hassium$LOGGER.debug("Wrote Hassium chunk {}: {} -> {} bytes",
                pos, rawNbtData.length, compressedLength);
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
