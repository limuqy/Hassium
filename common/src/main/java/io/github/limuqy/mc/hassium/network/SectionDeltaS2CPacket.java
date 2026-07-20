package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compression.CompressionException;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：分段增量响应（阶段二）
 * <p>
 * 服务端比对客户端的 section 哈希后，只发送变更的 section 数据和全部 blockEntity 数据。
 * 客户端组装：缓存的 sections + 新 sections + 实体数据。
 * <p>
 * {@code skipped}：本请求中因超视距等原因未处理的区块；客户端应立即回退全量。
 * 服务端对每次 SectionHashRequest 都会回包（entries/skipped 可空），避免客户端悬等。
 * <p>
 * 由客户端 {@code clientCache.sectionDeltaEnabled} 门控：开启时 MISMATCH 走分段增量，关闭时全量。
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

            // 变更的 sections
            buf.writeVarInt(entry.changedSections.size());
            for (SectionData section : entry.changedSections) {
                buf.writeVarInt(section.sectionIndex);
                buf.writeVarInt(section.blockData.length);
                buf.writeBytes(section.blockData);
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
        List<DeltaEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();

            // 变更的 sections
            int sectionCount = buf.readVarInt();
            List<SectionData> sections = new ArrayList<>(sectionCount);
            for (int j = 0; j < sectionCount; j++) {
                int sectionIndex = buf.readVarInt();
                int dataLen = buf.readVarInt();
                byte[] blockData = new byte[dataLen];
                buf.readBytes(blockData);
                sections.add(new SectionData(sectionIndex, blockData));
            }

            // blockEntity 数据
            int beCount = buf.readVarInt();
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

            entries.add(new DeltaEntry(chunkX, chunkZ, sections, blockEntities));
        }

        List<SkippedChunk> skipped = new ArrayList<>();
        if (buf.isReadable()) {
            int skippedCount = buf.readVarInt();
            skipped = new ArrayList<>(skippedCount);
            for (int i = 0; i < skippedCount; i++) {
                skipped.add(new SkippedChunk(buf.readVarInt(), buf.readVarInt()));
            }
        }
        return new SectionDeltaS2CPacket(dimension, entries, skipped);
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
            List<BlockEntityData> blockEntities
    ) {}

    /**
     * 请求中被服务端跳过的区块（客户端应回退全量）
     */
    public record SkippedChunk(int chunkX, int chunkZ) {}

    /**
     * 变更的 section 数据
     */
    public record SectionData(int sectionIndex, byte[] blockData) {}

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
