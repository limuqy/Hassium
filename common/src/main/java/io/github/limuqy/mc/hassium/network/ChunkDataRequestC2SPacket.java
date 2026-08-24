package io.github.limuqy.mc.hassium.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 -> 服务端：请求区块数据 / hash 比对回执
 * <p>
 * 客户端缓存未命中时，向服务端请求指定区块的完整数据（result = miss）；
 * 影子端 hash 比对完成后无论命中与否都回发本帧（hit 时 chunks 为空列表）。
 */
public record ChunkDataRequestC2SPacket(
        String dimension,
        List<ChunkPos> chunks,
        int result
) {
    /** result：缓存未命中，chunks 为缺失柱列表。 */
    public static final int RESULT_MISS = 0;
    /** result：hash 全部命中，chunks 为空列表。 */
    public static final int RESULT_HIT = 1;
    /** 单帧请求上限，防异常包放大内存。 */
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
        if (result != RESULT_MISS && result != RESULT_HIT) {
            throw new IllegalArgumentException("Invalid chunk request result: " + result);
        }
        if (result == RESULT_HIT && !chunks.isEmpty()) {
            throw new IllegalArgumentException("Hit result must not carry chunk positions");
        }
        if (result == RESULT_MISS && chunks.isEmpty()) {
            throw new IllegalArgumentException("Miss result must carry missing chunk positions");
        }
    }

    /** 是否需要服务端投递缺失柱的 full payload。 */
    public boolean requestsFullChunks() {
        return result == RESULT_MISS;
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
        buf.writeByte(result);
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
        List<ChunkPos> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = buf.readVarInt();
            int z = buf.readVarInt();
            chunks.add(new ChunkPos(x, z));
        }
        if (!buf.isReadable()) {
            throw new IllegalArgumentException("Truncated chunk request payload");
        }
        int result = buf.readByte();
        if (buf.isReadable()) {
            throw new IllegalArgumentException("Malformed chunk request payload");
        }
        return new ChunkDataRequestC2SPacket(dimension, chunks, result);
    }
}
