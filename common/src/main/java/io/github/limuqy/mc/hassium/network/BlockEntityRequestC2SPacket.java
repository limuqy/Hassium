package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import net.minecraft.world.level.ChunkPos;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 -> 服务端：请求 blockEntity 数据（阶段一缓存命中后）
 * <p>
 * 客户端缓存命中时，方块数据从缓存加载，但 blockEntity 不缓存，
 * 需要从服务端获取最新数据。此包只请求 blockEntity，不传输完整区块。
 */
public record BlockEntityRequestC2SPacket(
        String dimension,
        List<ChunkPos> chunks
) {
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHANNEL = ResourceLocationCompat.create(Constants.MOD_ID, "block_entity_request_c2s");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension);
        buf.writeVarInt(chunks.size());
        for (ChunkPos pos : chunks) {
            buf.writeVarInt(pos.x);
            buf.writeVarInt(pos.z);
        }
    }

    public static BlockEntityRequestC2SPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        int size = buf.readVarInt();
        List<ChunkPos> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = buf.readVarInt();
            int z = buf.readVarInt();
            chunks.add(new ChunkPos(x, z));
        }
        return new BlockEntityRequestC2SPacket(dimension, chunks);
    }
}
