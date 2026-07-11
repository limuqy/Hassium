package io.github.limuqy.mc.hassium.cache.client;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 缓存区块信息（精简版）
 * <p>
 * 只包含区块位置和时间戳，用于批量缓存查询。
 * 算法、版本等信息在包级别统一指定，不逐区块传输。
 */
public record CachedChunkInfo(
        int chunkX,
        int chunkZ,
        long timestamp
) {
    /**
     * 编码到网络缓冲区（精简格式：~12字节/条目）
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(chunkX);
        buf.writeVarInt(chunkZ);
        buf.writeLong(timestamp);  // 固定8字节，比VarLong更稳定
    }

    /**
     * 从网络缓冲区解码
     */
    public static CachedChunkInfo decode(FriendlyByteBuf buf) {
        int chunkX = buf.readVarInt();
        int chunkZ = buf.readVarInt();
        long timestamp = buf.readLong();
        return new CachedChunkInfo(chunkX, chunkZ, timestamp);
    }

    /**
     * 获取编码大小（用于预估包大小）
     */
    public static int estimateSize() {
        return 2 + 2 + 8; // VarInt + VarInt + Long ≈ 12字节
    }
}
