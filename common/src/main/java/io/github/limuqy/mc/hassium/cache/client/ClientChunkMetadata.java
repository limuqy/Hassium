package io.github.limuqy.mc.hassium.cache.client;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端区块缓存元数据
 * <p>
 * 坐标 + contentHash64，用于与服务端元数据相等比对。
 */
public record ClientChunkMetadata(
        int chunkX,
        int chunkZ,
        long contentHash
) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(chunkX);
        buf.writeVarInt(chunkZ);
        buf.writeLong(contentHash);
    }

    public static ClientChunkMetadata decode(FriendlyByteBuf buf) {
        int chunkX = buf.readVarInt();
        int chunkZ = buf.readVarInt();
        long contentHash = buf.readLong();
        return new ClientChunkMetadata(chunkX, chunkZ, contentHash);
    }
}
