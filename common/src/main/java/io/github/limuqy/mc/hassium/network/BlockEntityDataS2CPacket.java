package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：blockEntity 数据响应
 * <p>
 * 服务端收到 blockEntity 请求后，收集指定区块的所有 blockEntity 数据并发送。
 * 比发送完整区块数据（~50-100KB）节省约 98% 带宽。
 */
public record BlockEntityDataS2CPacket(
        String dimension,
        List<ChunkBlockEntities> entries
) {
    public static final ResourceLocation CHANNEL = new ResourceLocation(Constants.MOD_ID, "block_entity_data_s2c");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension);
        buf.writeVarInt(entries.size());
        for (ChunkBlockEntities entry : entries) {
            buf.writeVarInt(entry.chunkX);
            buf.writeVarInt(entry.chunkZ);
            buf.writeVarInt(entry.blockEntities.size());
            for (BlockEntityData be : entry.blockEntities) {
                buf.writeBlockPos(be.pos);
                buf.writeUtf(be.type.toString());
                buf.writeNbt(be.nbt);
            }
        }
    }

    public static BlockEntityDataS2CPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        int size = buf.readVarInt();
        List<ChunkBlockEntities> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            int beCount = buf.readVarInt();
            List<BlockEntityData> blockEntities = new ArrayList<>(beCount);
            for (int j = 0; j < beCount; j++) {
                BlockPos pos = buf.readBlockPos();
                ResourceLocation type = new ResourceLocation(buf.readUtf());
                CompoundTag nbt = buf.readNbt();
                blockEntities.add(new BlockEntityData(pos, type, nbt));
            }
            entries.add(new ChunkBlockEntities(chunkX, chunkZ, blockEntities));
        }
        return new BlockEntityDataS2CPacket(dimension, entries);
    }

    /**
     * 单个区块的 blockEntity 数据
     */
    public record ChunkBlockEntities(
            int chunkX,
            int chunkZ,
            List<BlockEntityData> blockEntities
    ) {}

    /**
     * 单个 blockEntity 数据
     */
    public record BlockEntityData(BlockPos pos, ResourceLocation type, CompoundTag nbt) {}
}
