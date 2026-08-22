package io.github.limuqy.mc.hassium.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 -> 服务端：请求区块数据
 * <p>
 * 客户端缓存未命中时，向服务端请求指定区块的完整数据。
 */
public record ChunkDataRequestC2SPacket(
        String dimension,
        List<ChunkPos> chunks,
        long fallbackDeliveryId
) {
    /** 单帧请求上限；与服务端 per-player admission pending 上限一致，防异常包放大内存。 */
    public static final int MAX_CHUNKS_PER_REQUEST = 384;

    /** 超过单帧上限时按序切批，避免构造抛错导致整批 C2S 未发出、客户端却已登记。 */
    public static List<List<ChunkPos>> partition(List<ChunkPos> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<List<ChunkPos>> batches = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += MAX_CHUNKS_PER_REQUEST) {
            int end = Math.min(i + MAX_CHUNKS_PER_REQUEST, chunks.size());
            batches.add(List.copyOf(chunks.subList(i, end)));
        }
        return batches;
    }

    public ChunkDataRequestC2SPacket {
        if (dimension == null || chunks == null) {
            throw new IllegalArgumentException("ChunkDataRequest fields must not be null");
        }
        if (chunks.size() > MAX_CHUNKS_PER_REQUEST) {
            throw new IllegalArgumentException("Too many chunk requests: " + chunks.size());
        }
        if (fallbackDeliveryId < 0L) {
            throw new IllegalArgumentException("fallbackDeliveryId must be non-negative");
        }
    }

    /**
     * 编码到网络缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension);
        buf.writeVarInt(chunks.size());
        for (ChunkPos pos : chunks) {
            buf.writeVarInt(pos.x);
            buf.writeVarInt(pos.z);
        }
        buf.writeLong(fallbackDeliveryId);
    }

    /**
     * 从网络缓冲区解码
     */
    public static ChunkDataRequestC2SPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_CHUNKS_PER_REQUEST) {
            throw new IllegalArgumentException("Invalid chunk request count: " + size);
        }
        if (buf.readableBytes() < Long.BYTES) {
            throw new IllegalArgumentException("Truncated chunk request payload");
        }
        List<ChunkPos> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = buf.readVarInt();
            int z = buf.readVarInt();
            chunks.add(new ChunkPos(x, z));
        }
        long fallbackDeliveryId = buf.readLong();
        return new ChunkDataRequestC2SPacket(dimension, chunks, fallbackDeliveryId);
    }
}
