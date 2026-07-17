package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.FriendlyByteBuf;

/**
 * {@link net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData} 线格式桥接。
 * <p>
 * 1.21.5 起 heightmaps 由 NBT 改为 {@code StreamCodec}（{@code Map<Heightmap.Types, long[]>}）。
 * 解析缓存中的 chunk packet 字节时必须经此兼容层跳过/复制，否则 section 偏移错位，
 * sectionHashes 计算失败 → 缓存命中率恒为 0。
 */
public final class ChunkPacketDataCompat {
    private ChunkPacketDataCompat() {}

#if MC_VER >= MC_1_21_5
    private static final net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf,
            java.util.Map<net.minecraft.world.level.levelgen.Heightmap.Types, long[]>> HEIGHTMAPS_STREAM_CODEC =
            net.minecraft.network.codec.ByteBufCodecs.map(
                    size -> new java.util.EnumMap<>(net.minecraft.world.level.levelgen.Heightmap.Types.class),
                    net.minecraft.world.level.levelgen.Heightmap.Types.STREAM_CODEC,
                    net.minecraft.network.codec.ByteBufCodecs.LONG_ARRAY
            );
#endif

    /**
     * 跳过 chunkData 中的 heightmaps 字段（readerIndex 前进到 sections varint）。
     */
    public static void skipHeightmaps(FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_5
        buf.readNbt();
#else
        HEIGHTMAPS_STREAM_CODEC.decode(buf);
#endif
    }

    /**
     * 将 heightmaps 字段从 {@code src} 原样复制到 {@code dst}。
     */
    public static void copyHeightmaps(FriendlyByteBuf src, FriendlyByteBuf dst) {
#if MC_VER < MC_1_21_5
        dst.writeNbt(src.readNbt());
#else
        HEIGHTMAPS_STREAM_CODEC.encode(dst, HEIGHTMAPS_STREAM_CODEC.decode(src));
#endif
    }
}
