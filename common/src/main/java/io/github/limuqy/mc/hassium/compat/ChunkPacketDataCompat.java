package io.github.limuqy.mc.hassium.compat;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
#if MC_VER >= MC_1_20_5
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
#endif
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * {@link net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData} 线格式桥接。
 * <p>
 * 1.21.5 起 heightmaps 由 NBT 改为 {@code StreamCodec}（{@code Map<Heightmap.Types, long[]>}）。
 * 解析缓存中的 chunk packet 字节时必须经此兼容层跳过/复制，否则 section 偏移错位，
 * sectionHashes 计算失败 → 缓存命中率恒为 0。
 * <p>
 * 另提供 section / heightmaps 的 NBT 读写辅助，供 {@code ChunkDiskCodec} 使用。
 * 跨版本差异内聚在本文件，业务代码不散落 {@code #if MC_VER}。
 */
public final class ChunkPacketDataCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ChunkPacketDataCompat");

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

    /**
     * 从 {@code src} 读取 heightmaps 并转为 {@link CompoundTag}（按原版 chunk NBT schema）。
     * <p>
     * 1.21.5-: heightmaps 本身就是 CompoundTag，直接返回。
     * 1.21.5+: Map&lt;Heightmap.Types, long[]&gt; 转为 CompoundTag（每个 entry 一个 LongArrayTag）。
     */
    public static CompoundTag readHeightmapsAsNbt(FriendlyByteBuf src) {
#if MC_VER < MC_1_21_5
        return src.readNbt();
#else
        Map<Heightmap.Types, long[]> map = HEIGHTMAPS_STREAM_CODEC.decode(src);
        CompoundTag tag = new CompoundTag();
        for (var entry : map.entrySet()) {
            tag.put(entry.getKey().getSerializedName(), new net.minecraft.nbt.LongArrayTag(entry.getValue()));
        }
        return tag;
#endif
    }

    /**
     * 将 heightmaps {@link CompoundTag} 写入 {@code dst}（与 {@link #readHeightmapsAsNbt} 对偶）。
     */
    public static void writeHeightmapsFromNbt(FriendlyByteBuf dst, CompoundTag heightmaps) {
#if MC_VER < MC_1_21_5
        dst.writeNbt(heightmaps);
#else
        Map<Heightmap.Types, long[]> map = new java.util.EnumMap<>(Heightmap.Types.class);
        for (String key : heightmaps.keySet()) {
            Heightmap.Types type = lookupHeightmapType(key);
            if (type == null) continue;
            Tag tag = heightmaps.get(key);
            if (!(tag instanceof net.minecraft.nbt.LongArrayTag lat)) continue;
            map.put(type, lat.getAsLongArray());
        }
        HEIGHTMAPS_STREAM_CODEC.encode(dst, map);
#endif
    }

    /** 按序列化名反向查找 {@link Heightmap.Types}（跨版本无 byName API）。 */
    private static Heightmap.Types lookupHeightmapType(String name) {
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (type.getSerializedName().equals(name)) return type;
        }
        return null;
    }

    /**
     * 把单个 {@link LevelChunkSection} 序列化为 {@link CompoundTag}。
     * <p>
     * 采用「线格式字节包装」策略：用原版 {@link LevelChunkSection#write} 写入 {@link FriendlyByteBuf}，
     * 再把字节包成 {@code "data": ByteArrayTag}。跨版本完全由原版 {@code write/read} 屏蔽，
     * 不依赖 {@code PalettedContainer} API 差异。
     *
     * @param section 目标 section
     * @return {@link CompoundTag}，含 {@code data}（线格式字节）与 {@code has_only_air} 标记
     */
    public static CompoundTag writeSection(LevelChunkSection section, RegistryAccess registryAccess) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("has_only_air", section.hasOnlyAir());

        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            section.write(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            tag.putByteArray("data", bytes);
        } finally {
            buf.release();
        }
        return tag;
    }

    /**
     * 把 {@link CompoundTag}（由 {@link #writeSection} 产出）读入既有 {@link LevelChunkSection}。
     * <p>
     * 复用调用方提供的 section 实例（通常经 {@link LevelChunkSectionCompat#create} 构造），
     * 避免在本方法内处理 PalettedContainer 构造的跨版本差异。
     *
     * @param sectionTag 由 {@link #writeSection} 产出的 CompoundTag
     * @param target     待填充的 section（调用方构造）
     */
    public static void readSectionInto(CompoundTag sectionTag, LevelChunkSection target,
                                       RegistryAccess registryAccess) {
        Tag dataTag = sectionTag.get("data");
        if (!(dataTag instanceof ByteArrayTag bat)) return;
        byte[] bytes = bat.getAsByteArray();
        if (bytes.length == 0) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        try {
            buf.readerIndex(0);
            target.read(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * 写入空的 {@code ClientboundLightUpdatePacketData} 线格式。
     * <p>
     * 磁盘 NBT 不存光照（{@code is_light_on=0}），HIT apply 重组
     * {@link net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket} 时必须带上合法的
     * 空光照尾部，否则构造函数在 {@code readBitSet} 处越界。客户端侧由原版光照引擎重算。
     */
    public static void writeEmptyLightData(FriendlyByteBuf buf) {
        BitSet empty = new BitSet();
        buf.writeBitSet(empty); // skyYMask
        buf.writeBitSet(empty); // blockYMask
        buf.writeBitSet(empty); // emptySkyYMask
        buf.writeBitSet(empty); // emptyBlockYMask
        // skyUpdates / blockUpdates：空列表（varint count = 0）
        // 1.20.2+ writeCollection 签名变为 StreamEncoder，且 writeByteArray 与
        // writeByteArray(ByteBuf, byte[]) 重载导致方法引用歧义，用 lambda 显式消除
        buf.writeCollection(List.<byte[]>of(), (b, arr) -> b.writeByteArray(arr));
        buf.writeCollection(List.<byte[]>of(), (b, arr) -> b.writeByteArray(arr));
    }

    /**
     * 读取 chunk packet 尾部的 BlockEntityInfo 列表，转为磁盘 NBT 用的 {@code block_entities}。
     * <p>
     * 原版线格式（非 CompoundTag 列表）：{@code count:VarInt} ×
     * {@code packedXZ:byte + y:short + type:registryId + tag:NBT}。
     * 旧实现误用 {@code readNbt()} 逐条读，几乎必失败并走异常路径——既慢又永远写不出 BE。
     *
     * @param buf            已读完 sections、指向 BE 列表起点
     * @param chunkX         区块 X（用于还原绝对坐标）
     * @param chunkZ         区块 Z
     * @param registryAccess 1.20.5+ 注册表（解码 BE type）
     */
    public static ListTag readBlockEntitiesAsNbt(FriendlyByteBuf buf, int chunkX, int chunkZ,
                                                 RegistryAccess registryAccess) {
        ListTag list = new ListTag();
        if (buf == null || buf.readableBytes() <= 0) {
            return list;
        }
        try {
#if MC_VER < MC_1_20_5
            int count = buf.readVarInt();
            for (int i = 0; i < count; i++) {
                int packedXZ = buf.readUnsignedByte();
                int y = buf.readShort();
                BlockEntityType<?> type = buf.readById(BuiltInRegistries.BLOCK_ENTITY_TYPE);
                CompoundTag tag = buf.readNbt();
                CompoundTag be = toBlockEntityNbt(packedXZ, y, type, tag, chunkX, chunkZ);
                if (be != null) {
                    list.add(be);
                }
            }
#else
            RegistryFriendlyByteBuf regBuf = buf instanceof RegistryFriendlyByteBuf rfb
                    ? rfb
                    : new RegistryFriendlyByteBuf(buf, registryAccess);
            int count = regBuf.readVarInt();
            var typeCodec = ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE);
            for (int i = 0; i < count; i++) {
                int packedXZ = regBuf.readUnsignedByte();
                int y = regBuf.readShort();
                BlockEntityType<?> type = typeCodec.decode(regBuf);
                CompoundTag tag = regBuf.readNbt();
                CompoundTag be = toBlockEntityNbt(packedXZ, y, type, tag, chunkX, chunkZ);
                if (be != null) {
                    list.add(be);
                }
            }
#endif
        } catch (Exception e) {
            // BE 解析失败不阻断 sections；已解析的保留
            LOGGER.debug("Hassium: readBlockEntitiesAsNbt partial failure at [{}, {}]", chunkX, chunkZ, e);
        }
        return list;
    }

    /**
     * 从 section NBT 列表中读取光照数据，按 {@code ClientboundLightUpdatePacketData} 线格式写入。
     * <p>
     * 遍历每个 section 的 {@code sky_light} / {@code block_light} ByteArrayTag，
     * 构建 BitSet 掩码和 DataLayer 列表。空 section 或缺失 tag 视为无数据。
     *
     * @param buf          目标缓冲区
     * @param sections     section NBT 列表（每个可能含 sky_light / block_light）
     * @param sectionCount section 总数
     */
    public static void writeLightDataFromNbt(FriendlyByteBuf buf, ListTag sections, int sectionCount) {
        BitSet skyYMask = new BitSet();
        BitSet blockYMask = new BitSet();
        BitSet emptySkyYMask = new BitSet();
        BitSet emptyBlockYMask = new BitSet();
        java.util.ArrayList<byte[]> skyUpdates = new java.util.ArrayList<>();
        java.util.ArrayList<byte[]> blockUpdates = new java.util.ArrayList<>();

        for (int i = 0; i < sectionCount && i < sections.size(); i++) {
            Tag t = sections.get(i);
            if (!(t instanceof CompoundTag sectionTag)) continue;

            Tag skyTag = sectionTag.get("sky_light");
            if (skyTag instanceof ByteArrayTag skyBat) {
                byte[] skyBytes = skyBat.getAsByteArray();
                if (skyBytes.length == 2048) {
                    if (isAllZero(skyBytes)) {
                        emptySkyYMask.set(i);
                    } else {
                        skyYMask.set(i);
                        skyUpdates.add(skyBytes);
                    }
                }
            }

            Tag blockTag = sectionTag.get("block_light");
            if (blockTag instanceof ByteArrayTag blockBat) {
                byte[] blockBytes = blockBat.getAsByteArray();
                if (blockBytes.length == 2048) {
                    if (isAllZero(blockBytes)) {
                        emptyBlockYMask.set(i);
                    } else {
                        blockYMask.set(i);
                        blockUpdates.add(blockBytes);
                    }
                }
            }
        }

        buf.writeBitSet(skyYMask);
        buf.writeBitSet(blockYMask);
        buf.writeBitSet(emptySkyYMask);
        buf.writeBitSet(emptyBlockYMask);
        buf.writeCollection(skyUpdates, (b, arr) -> b.writeByteArray(arr));
        buf.writeCollection(blockUpdates, (b, arr) -> b.writeByteArray(arr));
    }

    /**
     * 从 packet 字节流读取光照数据，存入对应 section 的 NBT。
     * <p>
     * 读取顺序：skyYMask → blockYMask → emptySkyYMask → emptyBlockYMask → skyUpdates → blockUpdates。
     * 读取后为每个 section 添加 {@code sky_light} / {@code block_light} ByteArrayTag。
     *
     * @param buf          已定位到光照数据起点的缓冲区
     * @param sectionCount section 总数
     * @param sections     section NBT 列表（将被修改）
     */
    public static void readLightDataFromPacket(FriendlyByteBuf buf, int sectionCount, ListTag sections) {
        BitSet skyYMask = buf.readBitSet();
        BitSet blockYMask = buf.readBitSet();
        BitSet emptySkyYMask = buf.readBitSet();
        BitSet emptyBlockYMask = buf.readBitSet();

        List<byte[]> skyUpdates = buf.readCollection(java.util.ArrayList::new, FriendlyByteBuf::readByteArray);
        List<byte[]> blockUpdates = buf.readCollection(java.util.ArrayList::new, FriendlyByteBuf::readByteArray);

        int skyIdx = 0;
        int blockIdx = 0;

        for (int i = 0; i < sectionCount && i < sections.size(); i++) {
            Tag t = sections.get(i);
            if (!(t instanceof CompoundTag sectionTag)) continue;

            if (skyYMask.get(i) && skyIdx < skyUpdates.size()) {
                sectionTag.putByteArray("sky_light", skyUpdates.get(skyIdx++));
            } else if (emptySkyYMask.get(i)) {
                sectionTag.putByteArray("sky_light", EMPTY_LIGHT);
            }

            if (blockYMask.get(i) && blockIdx < blockUpdates.size()) {
                sectionTag.putByteArray("block_light", blockUpdates.get(blockIdx++));
            } else if (emptyBlockYMask.get(i)) {
                sectionTag.putByteArray("block_light", EMPTY_LIGHT);
            }
        }
    }

    private static final byte[] EMPTY_LIGHT = new byte[2048];

    private static boolean isAllZero(byte[] data) {
        for (byte b : data) {
            if (b != 0) return false;
        }
        return true;
    }

    private static CompoundTag toBlockEntityNbt(int packedXZ, int y, BlockEntityType<?> type,
                                                CompoundTag tag, int chunkX, int chunkZ) {
        if (type == null) {
            return null;
        }
        var typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        if (typeId == null) {
            return null;
        }
        CompoundTag be = tag != null ? tag.copy() : new CompoundTag();
        be.putString("id", typeId.toString());
        int localX = (packedXZ >> 4) & 15;
        int localZ = packedXZ & 15;
        be.putInt("x", (chunkX << 4) + localX);
        be.putInt("y", y);
        be.putInt("z", (chunkZ << 4) + localZ);
        return be;
    }
}
