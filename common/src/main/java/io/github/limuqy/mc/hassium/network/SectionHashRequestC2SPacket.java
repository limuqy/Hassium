package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;
import io.netty.handler.codec.DecoderException;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 -> 服务端：请求 section 详情（阶段二）
 * <p>
 * 客户端在 chunkHash 不匹配时发送，携带本地缓存的 section 哈希。
 * 每个非 0 的 sectionHash 后紧跟 48 × u32 平面综合征。
 * 服务端比对后，只发送变更的 section 数据和全部 blockEntity 数据。
 */
public record SectionHashRequestC2SPacket(
        String dimension,
        List<Entry> entries
) {
    /** 与 {@code SectionDeltaS2CPacket} 单 chunk section 数上限对齐（1.18+ ≤ 24，留余量）。 */
    private static final int MAX_SECTIONS = 64;

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
            for (int i = 0; i < entry.sectionHashes.length; i++) {
                long hash = entry.sectionHashes[i];
                buf.writeLong(hash);
                if (hash != 0L) {
                    int[] planes = entry.planes(i);
                    for (int p = 0; p < SectionPlaneSyndrome.PLANE_COUNT; p++) {
                        buf.writeInt(planes != null && p < planes.length ? planes[p] : 0);
                    }
                }
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
            checkDecodeLimit(hashCount, MAX_SECTIONS, "sectionCount");
            long[] sectionHashes = new long[hashCount];
            int[][] planes = new int[hashCount][];
            for (int j = 0; j < hashCount; j++) {
                sectionHashes[j] = buf.readLong();
                if (sectionHashes[j] != 0L) {
                    int[] plane = new int[SectionPlaneSyndrome.PLANE_COUNT];
                    for (int p = 0; p < SectionPlaneSyndrome.PLANE_COUNT; p++) {
                        plane[p] = buf.readInt();
                    }
                    planes[j] = plane;
                }
            }
            entries.add(new Entry(chunkX, chunkZ, sectionHashes, planes));
        }
        return new SectionHashRequestC2SPacket(dimension, entries);
    }

    private static void checkDecodeLimit(int value, int max, String what) {
        if (value < 0 || value > max) {
            throw new DecoderException("SectionHashRequestC2SPacket " + what + " too large: " + value
                    + " (max " + max + ")");
        }
    }

    /**
     * 单个 chunk 的 section 哈希请求
     *
     * @param chunkX         区块 X 坐标
     * @param chunkZ         区块 Z 坐标
     * @param sectionHashes  客户端缓存的 per-section 哈希数组（索引 = section index）
     * @param planes         与 hashes 对齐；空气段（hash=0）为 null，非空段为 48 × u32
     */
    public record Entry(int chunkX, int chunkZ, long[] sectionHashes, int[][] planes) {
        public Entry(int chunkX, int chunkZ, long[] sectionHashes) {
            this(chunkX, chunkZ, sectionHashes, new int[sectionHashes != null ? sectionHashes.length : 0][]);
        }

        public int[] planes(int sectionIndex) {
            if (planes == null || sectionIndex < 0 || sectionIndex >= planes.length) {
                return null;
            }
            return planes[sectionIndex];
        }
    }
}
