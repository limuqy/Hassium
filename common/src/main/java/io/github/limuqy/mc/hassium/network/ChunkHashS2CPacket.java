package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：区块哈希广播（阶段一）
 * <p>
 * 轻量广播，每个 chunk 仅 12 字节（chunkHash 8B + sectionBitmap 4B）。
 * 客户端比对本地缓存的 chunkHash，匹配则缓存命中，不匹配则进入阶段二。
 */
public record ChunkHashS2CPacket(
        String dimension,
        List<Entry> entries
) {
    public static final ResourceLocation CHANNEL = new ResourceLocation(Constants.MOD_ID, "chunk_hash_s2c");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.chunkX);
            buf.writeVarInt(entry.chunkZ);
            buf.writeLong(entry.chunkHash);
            buf.writeInt(entry.sectionBitmap);
        }
    }

    public static ChunkHashS2CPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            long chunkHash = buf.readLong();
            int sectionBitmap = buf.readInt();
            entries.add(new Entry(chunkX, chunkZ, chunkHash, sectionBitmap));
        }
        return new ChunkHashS2CPacket(dimension, entries);
    }

    /**
     * 单个 chunk 的哈希条目
     *
     * @param chunkX         区块 X 坐标
     * @param chunkZ         区块 Z 坐标
     * @param chunkHash      chunk 级哈希（xxHash64，不含 blockEntity）
     * @param sectionBitmap  哪些 section 有方块数据（bit 位表示，bit i = 1 表示 section i 有数据）
     */
    public record Entry(int chunkX, int chunkZ, long chunkHash, int sectionBitmap) {}
}
