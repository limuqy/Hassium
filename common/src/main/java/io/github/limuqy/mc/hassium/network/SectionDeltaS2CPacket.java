package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
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
 * 服务端 -> 客户端：section delta 响应（阶段二）
 * <p>
 * 服务端比对客户端的 section 哈希后，只发送变更的 section 数据和全部 blockEntity 数据。
 * 客户端组装：缓存的 sections + 新 sections + 实体数据。
 * <p>
 * <b>阶段二暂禁用</b>：生产路径 miss/mismatch 一律走全量请求；本协议与 handler 保留供后续恢复。
 */
public record SectionDeltaS2CPacket(
        String dimension,
        List<DeltaEntry> entries
) {
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHANNEL = ResourceLocationCompat.create(Constants.MOD_ID, "section_delta_s2c");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension);
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
    }

    public static SectionDeltaS2CPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
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
        return new SectionDeltaS2CPacket(dimension, entries);
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
