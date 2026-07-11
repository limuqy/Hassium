package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
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
 * 客户端 -> 服务端：请求 section 详情（阶段二）
 * <p>
 * 客户端在 chunkHash 不匹配时发送，携带本地缓存的 section 哈希。
 * 服务端比对后，只发送变更的 section 数据和全部 blockEntity 数据。
 */
public record SectionHashRequestC2SPacket(
        String dimension,
        List<Entry> entries
) {
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHANNEL = ResourceLocationCompat.create(Constants.MOD_ID, "section_hash_request_c2s");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.chunkX);
            buf.writeVarInt(entry.chunkZ);
            buf.writeVarInt(entry.sectionHashes.length);
            for (long hash : entry.sectionHashes) {
                buf.writeLong(hash);
            }
        }
    }

    public static SectionHashRequestC2SPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            int hashCount = buf.readVarInt();
            long[] sectionHashes = new long[hashCount];
            for (int j = 0; j < hashCount; j++) {
                sectionHashes[j] = buf.readLong();
            }
            entries.add(new Entry(chunkX, chunkZ, sectionHashes));
        }
        return new SectionHashRequestC2SPacket(dimension, entries);
    }

    /**
     * 单个 chunk 的 section 哈希请求
     *
     * @param chunkX         区块 X 坐标
     * @param chunkZ         区块 Z 坐标
     * @param sectionHashes  客户端缓存的 per-section 哈希数组（索引 = section index）
     */
    public record Entry(int chunkX, int chunkZ, long[] sectionHashes) {}
}
