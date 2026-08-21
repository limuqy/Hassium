package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compression.CompressionException;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：分段增量响应（阶段二）
 * <p>
 * 服务端比对客户端的 section 哈希 + 平面综合征后，发送变更 section（整段 FULL 或
 * 方块列表 BLOCKS）和全部 blockEntity 数据。客户端组装：缓存的 sections + 新数据 + 实体。
 * <p>
 * {@code skipped}：本请求中因超视距等原因未处理的区块；客户端应立即回退全量。
 * 服务端对每次 SectionHashRequest 都会回包（entries/skipped 可空），避免客户端悬等。
 * <p>
 * 由客户端 {@code chunk.sectionDeltaEnabled} 门控：开启时 MISMATCH 走分段增量，关闭时全量。
 * <p>
 * 独立 ZSTD 压缩：entries+skipped payload 经 ZSTD 压缩后发送（黑名单排除全局 Pipeline 压缩，
 * 避免双重压缩）。压缩比 < 1 时自动回退未压缩。
 */
public record SectionDeltaS2CPacket(
        String dimension,
        List<DeltaEntry> entries,
        List<SkippedChunk> skipped
) {
    public SectionDeltaS2CPacket(String dimension, List<DeltaEntry> entries) {
        this(dimension, entries, List.of());
    }
    /** review-fix: T3-53：单 section 数据上限，对齐原版 ClientboundLevelChunkPacketData.TWO_MEGABYTES */
    private static final int TWO_MEGABYTES = 2 * 1024 * 1024;
    /** review-fix: T3-53：单包 entries/skipped 上限（视距级请求批，留余量） */
    private static final int MAX_ENTRIES = 4096;
    /** review-fix: T3-53：单 chunk section 数上限（1.18+ ≤ 24） */
    private static final int MAX_SECTIONS = 64;
    /** review-fix: T3-53：单 chunk 方块实体数上限（16×16×24 方块位） */
    private static final int MAX_BLOCK_ENTITIES = 4096;
    /** BLOCKS 单段最多 4096 格（整段）。 */
    private static final int MAX_BLOCKS_PER_SECTION = 4096;
    public static final int KIND_FULL = 0;
    public static final int KIND_BLOCKS = 1;

    /** review-fix: T3-53：解码守卫——恶意/损坏包超限值驱动 new 数组/集合预分配 → OOM 客户端 */
    private static void checkDecodeLimit(int value, int max, String what) {
        if (value < 0 || value > max) {
            throw new DecoderException("SectionDeltaS2CPacket " + what + " too large: " + value
                    + " (max " + max + ")");
        }
    }


    public static final
    #if MC_VER < MC_1_21_11
ResourceLocation
    #else
Identifier
    #endif
CHANNEL = ResourceLocationCompat.create(Constants.MOD_ID, "section_delta_s2c");

    /**
     * 序列化 entries+skipped 到 buf（不含 dimension 和压缩头）。
     */
    private void encodePayload(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (DeltaEntry entry : entries) {
            buf.writeVarInt(entry.chunkX);
            buf.writeVarInt(entry.chunkZ);
            buf.writeLong(entry.expectedChunkHash);

            // 变更的 sections
            buf.writeVarInt(entry.changedSections.size());
            for (SectionData section : entry.changedSections) {
                buf.writeVarInt(section.sectionIndex);
                buf.writeByte(section.kind);
                buf.writeVarInt(section.blockData.length);
                buf.writeBytes(section.blockData);
            }

            // heightmaps（整个 chunk 的 rawData；delta 不含 heightmap → 必须随包下发，
            // 否则 merge 后高度图过期）
            buf.writeVarInt(entry.heightmaps.size());
            for (HeightmapData hm : entry.heightmaps) {
                buf.writeVarInt(hm.typeId);
                buf.writeVarInt(hm.data.length);
                for (long v : hm.data) {
                    buf.writeLong(v);
                }
            }

            // blockEntity 数据
            buf.writeVarInt(entry.blockEntities.size());
            for (BlockEntityData be : entry.blockEntities) {
                buf.writeBlockPos(be.pos);
                buf.writeUtf(be.type.toString());
                buf.writeNbt(be.nbt);
            }
        }

        buf.writeVarInt(skipped.size());
        for (SkippedChunk s : skipped) {
            buf.writeVarInt(s.chunkX);
            buf.writeVarInt(s.chunkZ);
        }
    }

    /**
     * 从 buf 解析 entries+skipped（不含 dimension 和压缩头）。
     */
    private static SectionDeltaS2CPacket decodePayload(String dimension, FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        checkDecodeLimit(size, MAX_ENTRIES, "entries");
        List<DeltaEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            long expectedChunkHash = buf.readLong();

            // 变更的 sections
            int sectionCount = buf.readVarInt();
            checkDecodeLimit(sectionCount, MAX_SECTIONS, "sectionCount");
            List<SectionData> sections = new ArrayList<>(sectionCount);
            for (int j = 0; j < sectionCount; j++) {
                int sectionIndex = buf.readVarInt();
                int kind = buf.readByte() & 0xFF;
                if (kind != KIND_FULL && kind != KIND_BLOCKS) {
                    throw new DecoderException("SectionDeltaS2CPacket unknown section kind: " + kind);
                }
                int dataLen = buf.readVarInt();
                checkDecodeLimit(dataLen, TWO_MEGABYTES, "section dataLen");
                byte[] blockData = new byte[dataLen];
                buf.readBytes(blockData);
                if (kind == KIND_BLOCKS) {
                    checkBlocksPayload(blockData);
                }
                sections.add(new SectionData(sectionIndex, kind, blockData));
            }

            // heightmaps（typeId = Heightmap.Types.ordinal()）
            int hmCount = buf.readVarInt();
            List<HeightmapData> heightmaps = new ArrayList<>(hmCount);
            for (int j = 0; j < hmCount; j++) {
                int typeId = buf.readVarInt();
                int dataLen = buf.readVarInt();
                checkDecodeLimit(dataLen, TWO_MEGABYTES / Long.BYTES, "heightmap dataLen");
                long[] data = new long[dataLen];
                for (int k = 0; k < dataLen; k++) {
                    data[k] = buf.readLong();
                }
                heightmaps.add(new HeightmapData(typeId, data));
            }

            // blockEntity 数据
            int beCount = buf.readVarInt();
            checkDecodeLimit(beCount, MAX_BLOCK_ENTITIES, "blockEntity count");
            List<BlockEntityData> blockEntities = new ArrayList<>(beCount);
            for (int j = 0; j < beCount; j++) {
                BlockPos pos = buf.readBlockPos();
#if MC_VER < MC_1_21_11
                ResourceLocation
#else
                Identifier
#endif
                type = ResourceLocationCompat.create(buf.readUtf());
                CompoundTag nbt = buf.readNbt();
                blockEntities.add(new BlockEntityData(pos, type, nbt));
            }

            entries.add(new DeltaEntry(chunkX, chunkZ, sections, heightmaps, blockEntities, expectedChunkHash));
        }

        List<SkippedChunk> skipped = new ArrayList<>();
        if (buf.isReadable()) {
            int skippedCount = buf.readVarInt();
            checkDecodeLimit(skippedCount, MAX_ENTRIES, "skipped count");
            skipped = new ArrayList<>(skippedCount);
            for (int i = 0; i < skippedCount; i++) {
                skipped.add(new SkippedChunk(buf.readVarInt(), buf.readVarInt()));
            }
        }
        return new SectionDeltaS2CPacket(dimension, entries, skipped);
    }

    private static void checkBlocksPayload(byte[] blockData) {
        FriendlyByteBuf inner = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(blockData));
        try {
            int count = inner.readVarInt();
            checkDecodeLimit(count, MAX_BLOCKS_PER_SECTION, "block count");
            for (int i = 0; i < count; i++) {
                inner.readVarLong();
            }
        } catch (IndexOutOfBoundsException e) {
            throw new DecoderException("SectionDeltaS2CPacket truncated BLOCKS payload");
        } finally {
            inner.release();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension);

        // 序列化 payload 到临时 buf
        FriendlyByteBuf payloadBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            encodePayload(payloadBuf);
            byte[] rawBytes = new byte[payloadBuf.readableBytes()];
            payloadBuf.getBytes(0, rawBytes);

            // ZSTD 压缩
            String algorithm = Constants.NETWORK_COMPRESSION_ALGORITHM;
            int level = HassiumConfigService.getNetworkCompressionLevel();
            byte[] compressed;
            try {
                compressed = CompressionService.getInstance().compress(rawBytes, algorithm, level);
            } catch (CompressionException e) {
                throw new RuntimeException("Failed to compress section delta payload", e);
            }

            if (compressed != null && compressed.length < rawBytes.length) {
                // 压缩更小：写 flag=1 + algorithm + originalSize + compressedLen + compressedBytes
                buf.writeByte(1);
                buf.writeUtf(algorithm);
                buf.writeVarInt(rawBytes.length);
                buf.writeVarInt(compressed.length);
                buf.writeBytes(compressed);
            } else {
                // 未压缩更小：写 flag=0 + rawLen + rawBytes
                buf.writeByte(0);
                buf.writeVarInt(rawBytes.length);
                buf.writeBytes(rawBytes);
            }
        } finally {
            payloadBuf.release();
        }
    }

    public static SectionDeltaS2CPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        byte flag = buf.readByte();

        FriendlyByteBuf payloadBuf;
        if (flag == 1) {
            // 压缩：读 algorithm + originalSize + compressedLen + compressedBytes → ZSTD 解压
            String algorithm = buf.readUtf();
            int originalSize = buf.readVarInt();
            int compressedLen = buf.readVarInt();
            checkDecodeLimit(compressedLen, TWO_MEGABYTES, "compressedLen");
            byte[] compressed = new byte[compressedLen];
            buf.readBytes(compressed);
            byte[] raw;
            try {
                raw = CompressionService.getInstance().decompress(compressed, algorithm);
            } catch (CompressionException e) {
                throw new RuntimeException("Failed to decompress section delta payload", e);
            }
            payloadBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(raw));
        } else {
            // 未压缩：读 rawLen + rawBytes
            int rawLen = buf.readVarInt();
            checkDecodeLimit(rawLen, TWO_MEGABYTES, "rawLen");
            byte[] raw = new byte[rawLen];
            buf.readBytes(raw);
            payloadBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(raw));
        }

        try {
            return decodePayload(dimension, payloadBuf);
        } finally {
            payloadBuf.release();
        }
    }

    /**
     * 单个 chunk 的 delta 数据
     */
    public record DeltaEntry(
            int chunkX,
            int chunkZ,
            List<SectionData> changedSections,
            List<HeightmapData> heightmaps,
            List<BlockEntityData> blockEntities,
            long expectedChunkHash
    ) {
        public DeltaEntry(int chunkX, int chunkZ, List<SectionData> changedSections,
                          List<BlockEntityData> blockEntities) {
            this(chunkX, chunkZ, changedSections, List.of(), blockEntities, 0L);
        }

        public DeltaEntry(int chunkX, int chunkZ, List<SectionData> changedSections,
                          List<HeightmapData> heightmaps, List<BlockEntityData> blockEntities) {
            this(chunkX, chunkZ, changedSections, heightmaps, blockEntities, 0L);
        }
    }

    /**
     * chunk 级高度图 rawData（typeId = {@code Heightmap.Types.ordinal()}）。
     * 服务端打包 chunk.getHeightmaps() 全量；客户端 apply 时逐 type setHeightmap。
     */
    public record HeightmapData(int typeId, long[] data) {}

    /**
     * 请求中被服务端跳过的区块（客户端应回退全量）
     */
    public record SkippedChunk(int chunkX, int chunkZ) {}

    /**
     * 变更的 section 数据。
     * {@code kind}：{@link #KIND_FULL} = {@code section.write} 字节；
     * {@link #KIND_BLOCKS} = VarInt 个数 + {@code varLong(stateId<<12 | localPos)}。
     */
    public record SectionData(int sectionIndex, int kind, byte[] blockData) {
        public SectionData(int sectionIndex, byte[] blockData) {
            this(sectionIndex, KIND_FULL, blockData);
        }
    }

    /**
     * 分片扣减用的变更格数：{@code FULL} / 未知 kind / 损坏 BLOCKS = 4096；
     * {@code BLOCKS} = 列表个数（封顶 4096）。
     */
    public static long changedCells(List<SectionData> sections) {
        if (sections == null || sections.isEmpty()) {
            return 0L;
        }
        long cells = 0L;
        for (SectionData sd : sections) {
            if (sd == null) {
                continue;
            }
            if (sd.kind() == KIND_BLOCKS) {
                int n = SectionPlaneSyndrome.peekBlockListCount(sd.blockData());
                if (n < 0) {
                    cells += SectionPlaneSyndrome.CELLS;
                } else {
                    cells += Math.min(n, SectionPlaneSyndrome.CELLS);
                }
            } else {
                cells += SectionPlaneSyndrome.CELLS;
            }
        }
        return cells;
    }

    /**
     * 方块实体数据
     */
    public record BlockEntityData(BlockPos pos,
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
 type, CompoundTag nbt) {}
}
