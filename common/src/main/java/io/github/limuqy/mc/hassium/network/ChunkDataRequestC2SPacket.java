package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
        List<ChunkPos> chunks
) {
    public static final ResourceLocation CHANNEL = new ResourceLocation(Constants.MOD_ID, "chunk_data_request_c2s");

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
    }

    /**
     * 从网络缓冲区解码
     */
    public static ChunkDataRequestC2SPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        int size = buf.readVarInt();
        List<ChunkPos> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = buf.readVarInt();
            int z = buf.readVarInt();
            chunks.add(new ChunkPos(x, z));
        }
        return new ChunkDataRequestC2SPacket(dimension, chunks);
    }
}
