package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：区块元数据包
 * <p>
 * 包含区块位置与 contentHash64，用于客户端缓存比对。
 */
public record ChunkMetadataS2CPacket(
        String dimension,
        List<MetadataEntry> entries
) {
    public static final ResourceLocation CHANNEL = new ResourceLocation(Constants.MOD_ID, "chunk_metadata_s2c");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension);
        buf.writeVarInt(entries.size());
        for (MetadataEntry entry : entries) {
            buf.writeVarInt(entry.chunkX);
            buf.writeVarInt(entry.chunkZ);
            buf.writeLong(entry.contentHash);
        }
    }

    public static ChunkMetadataS2CPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        int size = buf.readVarInt();
        List<MetadataEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            long contentHash = buf.readLong();
            entries.add(new MetadataEntry(chunkX, chunkZ, contentHash));
        }
        return new ChunkMetadataS2CPacket(dimension, entries);
    }

    public record MetadataEntry(int chunkX, int chunkZ, long contentHash) {}
}
