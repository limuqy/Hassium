package io.github.limuqy.mc.hassium.cache;

import com.google.common.hash.Hashing;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 区块内容哈希（64-bit）。
 * <p>
 * 输入域对齐 NEB：sections + 确定性 heightmap/BE，排除 LightData。
 * 生产算法：xxHash64（基准显著快于 Murmur3_64）。
 * <p>
 * 使用流式哈希计算，避免 ByteArrayOutputStream 临时对象分配。
 */
public final class ChunkContentHashUtil {

    private static final XXHashFactory XX_FACTORY = XXHashFactory.fastestInstance();
    private static final XXHash64 XX_HASH_64 = XX_FACTORY.hash64();
    private static final long HASH_SEED = 0L;

    private ChunkContentHashUtil() {}

    public record Result(long hash, int sectionsBytes) {}

    public static Result compute(ClientboundLevelChunkWithLightPacket packet) {
        return compute(packet.getChunkData(), packet.getX(), packet.getZ());
    }

    public static Result compute(LevelChunk chunk, LevelLightEngine lightEngine) {
        BitSet empty = new BitSet();
        ClientboundLevelChunkWithLightPacket packet =
                new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, empty, empty);
        return compute(packet);
    }

    @SuppressWarnings("deprecation")
    public static Result compute(ClientboundLevelChunkPacketData chunkData, int chunkX, int chunkZ) {
        FriendlyByteBuf sBuf = chunkData.getReadBuffer();
        int sectionsBytes = sBuf.readableBytes();
        byte[] sections = new byte[sectionsBytes];
        sBuf.getBytes(sBuf.readerIndex(), sections);

        List<BlockEntityEntry> entities = new ArrayList<>();
        chunkData.getBlockEntitiesTagsConsumer(chunkX, chunkZ).accept(
                (BlockPos pos, BlockEntityType<?> type, CompoundTag nbt) ->
                        entities.add(new BlockEntityEntry(pos.immutable(), type, nbt))
        );
        entities.sort(Comparator
                .comparingInt((BlockEntityEntry e) -> e.pos.getX())
                .thenComparingInt(e -> e.pos.getY())
                .thenComparingInt(e -> e.pos.getZ()));

        // 流式哈希：直接写入 StreamingXXHash64，无需中间 byte[]
        StreamingXXHash64 streamingHasher = XX_FACTORY.newStreamingHash64(HASH_SEED);
        HashingOutputStream out = new HashingOutputStream(streamingHasher);
        try {
            out.write(sections);
#if MC_VER < MC_1_21_5
            writeNbt(out, chunkData.getHeightmaps());
#else
            CompoundTag heightmapTag = new CompoundTag();
            for (var entry : chunkData.getHeightmaps().entrySet()) {
                heightmapTag.put(entry.getKey().getSerializedName(), new LongArrayTag(entry.getValue()));
            }
            writeNbt(out, heightmapTag);
#endif
            writeInt(out, entities.size());
            for (BlockEntityEntry e : entities) {
                writeInt(out, e.pos.getX());
                writeInt(out, e.pos.getY());
                writeInt(out, e.pos.getZ());
#if MC_VER < MC_1_21_11
                ResourceLocation
#else
                Identifier
#endif
                typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(e.type);
                writeString(out, typeId != null ? typeId.toString() : "");
                writeNbt(out, e.nbt);
            }

            long hash = out.getValue();
            if (hash == 0L) {
                hash = 1L;
            }
            return new Result(hash, sectionsBytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build chunk hash input", e);
        }
    }

    /**
     * 计算 chunk 中每个 section 的方块哈希（不含 blockEntity NBT）。
     * <p>
     * 空 section（hasOnlyAir）不包含在结果中。
     *
     * @param chunk 目标区块
     * @return sectionIndex -> xxHash64 的映射
     */
    public static Map<Integer, Long> computeSectionHashes(LevelChunk chunk) {
        Map<Integer, Long> hashes = new HashMap<>();
        LevelChunkSection[] sections = chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) {
                continue;
            }
            long hash = computeSectionHash(section);
            if (hash == 0L) {
                hash = 1L;
            }
            hashes.put(i, hash);
        }
        return hashes;
    }

    /**
     * 从 per-section 哈希组合出 chunk 级哈希。
     * <p>
     * 服务端和客户端使用相同的组合算法，确保一致性。
     * 使用流式哈希：依次写入 sectionIndex + sectionHash 的字节。
     *
     * @param sectionHashes sectionIndex -> xxHash64 的映射
     * @return 组合后的 chunkHash（0 值会被替换为 1）
     */
    public static long combineSectionHashes(Map<Integer, Long> sectionHashes) {
        if (sectionHashes.isEmpty()) {
            return 1L;
        }
        StreamingXXHash64 hasher = XX_FACTORY.newStreamingHash64(HASH_SEED);
        HashingOutputStream out = new HashingOutputStream(hasher);
        try {
            // 按 sectionIndex 排序，确保确定性
            List<Integer> indices = new ArrayList<>(sectionHashes.keySet());
            Collections.sort(indices);
            for (int idx : indices) {
                writeInt(out, idx);
                writeLong(out, sectionHashes.get(idx));
            }
            long hash = out.getValue();
            return hash == 0L ? 1L : hash;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to combine section hashes", e);
        }
    }

    /**
     * 从 section 哈希数组（long[]）组合出 chunk 级哈希。
     * <p>
     * 用于客户端从缓存读取 sectionHashes 后快速计算 chunkHash。
     * 数组索引即 sectionIndex，值为 0 表示空 section（跳过）。
     *
     * @param sectionHashes section 哈希数组（索引 = sectionIndex）
     * @return 组合后的 chunkHash（0 值会被替换为 1）
     */
    public static long combineSectionHashesFromArray(long[] sectionHashes) {
        if (sectionHashes == null || sectionHashes.length == 0) {
            return 1L;
        }
        StreamingXXHash64 hasher = XX_FACTORY.newStreamingHash64(HASH_SEED);
        HashingOutputStream out = new HashingOutputStream(hasher);
        try {
            for (int i = 0; i < sectionHashes.length; i++) {
                if (sectionHashes[i] == 0L) continue;
                writeInt(out, i);
                writeLong(out, sectionHashes[i]);
            }
            long hash = out.getValue();
            return hash == 0L ? 1L : hash;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to combine section hashes from array", e);
        }
    }

    /**
     * 将 section 哈希 Map 转换为 long[] 数组。
     * <p>
     * 数组大小 = max(sectionIndex) + 1，空 section 值为 0。
     *
     * @param sectionHashes sectionIndex -> xxHash64 的映射
     * @return section 哈希数组
     */
    public static long[] sectionHashesToArray(Map<Integer, Long> sectionHashes) {
        if (sectionHashes.isEmpty()) {
            return new long[0];
        }
        int maxIndex = Collections.max(sectionHashes.keySet());
        long[] array = new long[maxIndex + 1];
        for (var entry : sectionHashes.entrySet()) {
            array[entry.getKey()] = entry.getValue();
        }
        return array;
    }

    /**
     * 将 long[] 数组转换为 section 哈希 Map（跳过值为 0 的条目）。
     *
     * @param array section 哈希数组
     * @return sectionIndex -> xxHash64 的映射
     */
    public static Map<Integer, Long> arrayToSectionHashes(long[] array) {
        Map<Integer, Long> hashes = new HashMap<>();
        if (array == null) return hashes;
        for (int i = 0; i < array.length; i++) {
            if (array[i] != 0L) {
                hashes.put(i, array[i]);
            }
        }
        return hashes;
    }

    /**
     * 计算单个 section 的方块哈希（方块状态 + 生物群系，不含 blockEntity）。
     * <p>
     * 1.21.9+ 用 pack(Strategy) 规范化，避免 palette 排列变化导致 hash 不匹配。
     * 1.20.1-1.21.8 用 section.write() 字节（palette 排列稳定）。
     */
    public static long computeSectionHash(LevelChunkSection section) {
        StreamingXXHash64 hasher = XX_FACTORY.newStreamingHash64(HASH_SEED);
        HashingOutputStream out = new HashingOutputStream(hasher);
        try {
            LevelChunkSectionCompat.writeSectionForHash(section, out);
            return out.getValue();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to hash section", e);
        }
    }

    /**
     * 计算 chunk 级哈希（从 packet 数据）。
     * <p>
     * 排除 blockEntity NBT，只包含 sections + heightmaps。
     * 用于阶段一的 chunkHash 广播。
     */
    public static long computeChunkHashFromPacket(ClientboundLevelChunkWithLightPacket packet) {
        return computeChunkHashFromPacketData(packet.getChunkData(), packet.getX(), packet.getZ());
    }

    /**
     * 计算 chunk 级哈希（从 packet 数据）。
     * <p>
     * 排除 blockEntity NBT，只包含 sections + heightmaps。
     */
    public static long computeChunkHashFromPacketData(
            ClientboundLevelChunkPacketData chunkData, int chunkX, int chunkZ) {
        FriendlyByteBuf sBuf = chunkData.getReadBuffer();
        int sectionsBytes = sBuf.readableBytes();
        byte[] sections = new byte[sectionsBytes];
        sBuf.getBytes(sBuf.readerIndex(), sections);

        StreamingXXHash64 hasher = XX_FACTORY.newStreamingHash64(HASH_SEED);
        HashingOutputStream out = new HashingOutputStream(hasher);
        try {
            // 只写入 sections 数据，不写入 blockEntity
            out.write(sections);
#if MC_VER < MC_1_21_5
            writeNbt(out, chunkData.getHeightmaps());
#else
            CompoundTag heightmapTag = new CompoundTag();
            for (var entry : chunkData.getHeightmaps().entrySet()) {
                heightmapTag.put(entry.getKey().getSerializedName(), new LongArrayTag(entry.getValue()));
            }
            writeNbt(out, heightmapTag);
#endif

            long hash = out.getValue();
            return hash == 0L ? 1L : hash;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to compute chunk hash from packet", e);
        }
    }

    /**
     * 从原始 section 字节计算 per-section 哈希。
     * <p>
     * 使用原版 {@link LevelChunkSection#read} 定位边界，再对原始字节做 xxHash，
     * 与 {@link #computeSectionHash(LevelChunkSection)} 的输入域一致。
     *
     * @param sectionsBytes 原始 section 字节（从 packet 中提取）
     * @param sectionCount  section 数量
     * @param registryAccess 注册表访问（用于原版 PalettedContainer 解析）
     * @return sectionIndex -> xxHash64 的映射
     */
    public static Map<Integer, Long> computeSectionHashesFromBytes(
            byte[] sectionsBytes, int sectionCount, RegistryAccess registryAccess) {
        return parseAndHashSections(sectionsBytes, sectionCount, registryAccess);
    }

    /**
     * 计算 per-section 哈希（从 packet 数据）。
     * <p>
     * {@link ClientboundLevelChunkPacketData#getReadBuffer()} 仅包含 sections 原始字节，
     * 不含 length 前缀或 heightmaps。
     */
    public static Map<Integer, Long> computeSectionHashesFromPacket(
            ClientboundLevelChunkPacketData chunkData, int sectionCount,
            RegistryAccess registryAccess) {
        FriendlyByteBuf sBuf = chunkData.getReadBuffer();
        sBuf.readerIndex(0);

        int sectionsBytes = sBuf.readableBytes();
        byte[] sections = new byte[sectionsBytes];
        sBuf.getBytes(sBuf.readerIndex(), sections);

        return parseAndHashSections(sections, sectionCount, registryAccess);
    }

    /**
     * 解析 section 字节并计算 per-section 哈希。
     */
    private static Map<Integer, Long> parseAndHashSections(
            byte[] allData, int sectionCount, RegistryAccess registryAccess) {
        Map<Integer, Long> hashes = new HashMap<>();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(allData));
        try {
            LevelChunkSection scratch = LevelChunkSectionCompat.create(registryAccess);
            for (int i = 0; i < sectionCount && buf.readableBytes() > 0; i++) {
                scratch.read(buf);

                // 空 section（仅空气）不计入 chunkHash，与 computeSectionHashes 一致
                if (scratch.hasOnlyAir()) {
                    continue;
                }

                // 用 writeSectionForHash 统一哈希方式：
                // 1.21.9+ pack(Strategy) 规范化，1.20.1-1.21.8 section.write() 字节
                StreamingXXHash64 hasher = XX_FACTORY.newStreamingHash64(HASH_SEED);
                HashingOutputStream out = new HashingOutputStream(hasher);
                try {
                    LevelChunkSectionCompat.writeSectionForHash(scratch, out);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to hash section " + i, e);
                }
                long hash = out.getValue();
                if (hash == 0L) hash = 1L;
                hashes.put(i, hash);
            }
        } finally {
            buf.release();
        }
        return hashes;
    }

    /**
     * 跳过一个完整 LevelChunkSection（blockCount + blockStates + biomes）。
     * 使用原版 read，避免手动解析 PalettedContainer。
     */
    public static void skipOneSection(FriendlyByteBuf buf, RegistryAccess registryAccess) {
        LevelChunkSectionCompat.create(registryAccess).read(buf);
    }

    /**
     * 解析 sections 字节中每个 section 的 [start, end) 偏移。
     * <p>
     * 必须按 {@code sectionCount} 精确读取，不能用 {@code readableBytes() > 0} 循环——
     * 尾部残留字节会导致下一次 {@link LevelChunkSection#read} 读到半截数据而抛
     * {@link IndexOutOfBoundsException}。
     */
    public static List<int[]> parseSectionRanges(byte[] sectionsBytes, int sectionCount,
                                                   RegistryAccess registryAccess) {
        List<int[]> ranges = new ArrayList<>(sectionCount);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(sectionsBytes));
        try {
            LevelChunkSection scratch = LevelChunkSectionCompat.create(registryAccess);
            for (int i = 0; i < sectionCount; i++) {
                int start = buf.readerIndex();
                scratch.read(buf);
                ranges.add(new int[]{start, buf.readerIndex()});
            }
            if (buf.readableBytes() > 0) {
                Constants.LOG.warn("Hassium: {} trailing bytes after parsing {} sections",
                        buf.readableBytes(), sectionCount);
            }
        } finally {
            buf.release();
        }
        return ranges;
    }

    public static long xxHash64OfBytes(byte[] data, int off, int len) {
        return XX_HASH_64.hash(data, off, len, 0L);
    }

    public static long murmur3OfBytes(byte[] data) {
        return Hashing.murmur3_128().hashBytes(data).asLong();
    }

    public static long xxHash64OfBytes(byte[] data) {
        return XX_HASH_64.hash(data, 0, data.length, 0L);
    }

    private static void writeNbt(OutputStream out, Tag element) throws IOException {
        if (element == null) {
            out.write(0);
            return;
        }
        out.write(element.getId());
        if (element instanceof CompoundTag c) {
            List<String> keys = new ArrayList<>(CompoundTagCompat.getKeys(c));
            Collections.sort(keys);
            writeInt(out, keys.size());
            for (String key : keys) {
                writeString(out, key);
                writeNbt(out, c.get(key));
            }
        } else if (element instanceof ListTag l) {
            writeInt(out, l.size());
            for (Tag e : l) {
                writeNbt(out, e);
            }
        } else if (element instanceof ByteTag b) {
            out.write(CompoundTagCompat.getByte(b));
        } else if (element instanceof ShortTag s) {
            writeShort(out, CompoundTagCompat.getShort(s));
        } else if (element instanceof IntTag ni) {
            writeInt(out, CompoundTagCompat.getInt(ni));
        } else if (element instanceof LongTag nl) {
            writeLong(out, CompoundTagCompat.getLong(nl));
        } else if (element instanceof FloatTag f) {
            writeInt(out, Float.floatToIntBits(CompoundTagCompat.getFloat(f)));
        } else if (element instanceof DoubleTag d) {
            writeLong(out, Double.doubleToLongBits(CompoundTagCompat.getDouble(d)));
        } else if (element instanceof StringTag s) {
            writeString(out, CompoundTagCompat.getString(s));
        } else if (element instanceof ByteArrayTag a) {
            byte[] arr = a.getAsByteArray();
            writeInt(out, arr.length);
            out.write(arr);
        } else if (element instanceof IntArrayTag a) {
            int[] arr = a.getAsIntArray();
            writeInt(out, arr.length);
            for (int ia : arr) {
                writeInt(out, ia);
            }
        } else if (element instanceof LongArrayTag a) {
            long[] arr = a.getAsLongArray();
            writeInt(out, arr.length);
            for (long la : arr) {
                writeLong(out, la);
            }
        } else {
            Constants.LOG.warn("Hassium: Unknown NBT type {} in chunk hash", element.getId());
        }
    }

    private static void writeShort(OutputStream out, short v) throws IOException {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLong(OutputStream out, long v) throws IOException {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((v >>> (i * 8)) & 0xFF));
        }
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeInt(out, bytes.length);
        out.write(bytes);
    }

    /**
     * 流式哈希输出流：将写入的数据直接喂给 StreamingXXHash64，无需中间 byte[]
     */
    private static class HashingOutputStream extends OutputStream {
        private final StreamingXXHash64 hasher;

        HashingOutputStream(StreamingXXHash64 hasher) {
            this.hasher = hasher;
        }

        @Override
        public void write(int b) {
            hasher.update(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            hasher.update(b, off, len);
        }

        long getValue() {
            return hasher.getValue();
        }
    }

    private record BlockEntityEntry(BlockPos pos, BlockEntityType<?> type, CompoundTag nbt) {}
}
