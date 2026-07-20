package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.BitSet;
import java.util.Map;

/**
 * 客户端缓存磁盘 NBT 编解码入口。
 * <p>
 * 缓存 payload 从 packet 字节改为磁盘 chunk {@link CompoundTag}；外层仍为 {@code type 126} + MetadataTable。
 * <p>
 * <b>NBT schema</b>：
 * <pre>
 * {
 *   "x": IntTag,
 *   "z": IntTag,
 *   "y_pos": IntTag,                        // 最低 section Y（chunk.getSectionYFromIndex(0) 或等价）
 *   "section_count": IntTag,                // chunk.getSectionsCount()
 *   "sections": ListTag&lt;CompoundTag&gt;,   // 每个: {"data": ByteArrayTag (LevelChunkSection 线格式), "has_only_air": ByteTag}
 *   "heightmaps": CompoundTag,              // 1.21.5-: 直接 NBT；1.21.5+: Map&lt;Types,long[]&gt; 序列化为 CompoundTag
 *   "block_entities": ListTag&lt;CompoundTag&gt;, // 每个 BE 的完整 NBT（含 id / x / y / z）
 *   "is_light_on": ByteTag
 * }
 * </pre>
 * <p>
 * <b>跨版本策略</b>：section 用 {@link LevelChunkSection#write}/{@link LevelChunkSection#read} 屏蔽
 * PalettedContainer 差异；heightmaps 由 {@link ChunkPacketDataCompat} 屏蔽；BE NBT 由原版
 * {@link net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData} 内部 codec 处理。
 * <p>
 * <b>apply 路径</b>：{@link #nbtToPacketBytes} 重组 packet 字节 → {@code applyToLevelFromByteBuf}
 * （现有三端实现已就绪，不依赖占位实现的 {@code applyToLevel(CompoundTag)}）。
 */
public final class ChunkDiskCodec {
    private ChunkDiskCodec() {}

    /** chunk NBT 的 magic 前缀，用于区分旧 packet 字节缓存（无此前缀）。 */
    private static final byte[] NBT_MAGIC = "HBT1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /**
     * 解压后的 packet 字节 → {@link CompoundTag}（收包 warm-stash / 持久化共用）。
     * <p>
     * packet 线格式：{@code chunkX(4) + chunkZ(4) + heightmaps + sectionsSize(varint) + sections + BE list}。
     * 光照数据（chunk packet 尾部之后的 LightData）不进 NBT（apply 时由原版重算/请求）。
     *
     * @param packetBytes    packet 字节
     * @param registryAccess 注册表访问
     * @return chunk NBT；失败返回 null
     */
    public static CompoundTag packetBytesToNbt(byte[] packetBytes, RegistryAccess registryAccess) {
        return packetBytesToNbt(packetBytes, registryAccess, -1);
    }

    /**
     * @param expectedSectionCount 世界 section 数（如 {@code level.getSectionsCount()}）；≤0 时回退 guess
     */
    public static CompoundTag packetBytesToNbt(byte[] packetBytes, RegistryAccess registryAccess,
                                              int expectedSectionCount) {
        if (packetBytes == null || packetBytes.length < 8) return null;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(packetBytes));
        try {
            buf.readerIndex(0);
            int chunkX = buf.readInt();
            int chunkZ = buf.readInt();
            CompoundTag heightmaps = ChunkPacketDataCompat.readHeightmapsAsNbt(buf);
            int sectionsSize = buf.readVarInt();
            if (sectionsSize < 0 || sectionsSize > buf.readableBytes()) return null;
            byte[] sectionsBytes = new byte[sectionsSize];
            buf.readBytes(sectionsBytes);

            // 优先用世界 section 数：guess 可能把尾部残字节多读成第 25/26 个 section
            int guessed = guessSectionCount(sectionsBytes, registryAccess);
            int sectionCount = expectedSectionCount > 0 ? expectedSectionCount : guessed;
            if (expectedSectionCount > 0 && guessed != expectedSectionCount) {
                Constants.LOG.debug(
                        "Hassium: section count guess={} expected={}, using expected for [{}, {}]",
                        guessed, expectedSectionCount, chunkX, chunkZ);
            }
            ListTag sectionsList = parseSectionsToList(sectionsBytes, sectionCount, registryAccess);

            // 尾部：原版 BlockEntityInfo 线格式（见 ChunkPacketDataCompat）
            ListTag beList = ChunkPacketDataCompat.readBlockEntitiesAsNbt(
                    buf, chunkX, chunkZ, registryAccess);

            return buildChunkNbt(chunkX, chunkZ, sectionCount, sectionsList, heightmaps, beList);
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: packetBytesToNbt failed", e);
            return null;
        } finally {
            buf.release();
        }
    }

    /**
     * {@link CompoundTag} → packet 字节（HIT apply / delta merge 后 apply 共用）。
     * <p>
     * 重组 packet 线格式：{@code chunkX(4) + chunkZ(4) + heightmaps + sectionsSize(varint) + sections + BE list}。
     * 不含 LightData（apply 时原版会处理光照）。
     *
     * @param nbt            chunk NBT
     * @param registryAccess 注册表访问
     * @return packet 字节；失败返回 null
     */
    public static byte[] nbtToPacketBytes(CompoundTag nbt, RegistryAccess registryAccess) {
        return nbtToPacketBytes(nbt, registryAccess, -1);
    }

    /**
     * @param expectedSectionCount 世界 section 数；≤0 时用 NBT 内 section_count / list size
     */
    public static byte[] nbtToPacketBytes(CompoundTag nbt, RegistryAccess registryAccess,
                                         int expectedSectionCount) {
        if (nbt == null) return null;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buf.writeInt(CompoundTagCompat.getInt(nbt, "x", 0));
            buf.writeInt(CompoundTagCompat.getInt(nbt, "z", 0));

            Tag hmTag = nbt.get("heightmaps");
            CompoundTag heightmaps = hmTag instanceof CompoundTag ct ? ct : new CompoundTag();
            ChunkPacketDataCompat.writeHeightmapsFromNbt(buf, heightmaps);

            // 重组 sections 字节（优先按世界 section 数截断，丢掉旧缓存 phantom section）
            ListTag sectionsList = CompoundTagCompat.getList(nbt, "sections");
            int sectionLimit = expectedSectionCount > 0
                    ? expectedSectionCount
                    : CompoundTagCompat.getInt(nbt, "section_count", sectionsList.size());
            if (sectionLimit <= 0 || sectionLimit > sectionsList.size()) {
                sectionLimit = sectionsList.size();
            }
            java.io.ByteArrayOutputStream sectionsBos = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < sectionLimit; i++) {
                Tag t = sectionsList.get(i);
                if (t instanceof CompoundTag ct) {
                    Tag dataTag = ct.get("data");
                    if (dataTag instanceof ByteArrayTag bat) {
                        byte[] data = bat.getAsByteArray();
                        sectionsBos.write(data);
                    }
                }
            }
            byte[] sectionsBytes = sectionsBos.toByteArray();
            buf.writeVarInt(sectionsBytes.length);
            buf.writeBytes(sectionsBytes);

            // BE：原版线格式为 BlockEntityInfo，与 writeNbt 不兼容。
            // 命中路径由专用 BE 通道补发，此处一律写空列表，避免 apply 时误解析。
            buf.writeVarInt(0);

            // WithLight 包尾部：空光照（磁盘不存光，客户端重算）
            ChunkPacketDataCompat.writeEmptyLightData(buf);

            byte[] result = new byte[buf.readableBytes()];
            buf.getBytes(0, result);
            return result;
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: nbtToPacketBytes failed", e);
            return null;
        } finally {
            buf.release();
        }
    }

    /**
     * {@link LevelChunk} → {@link CompoundTag}（Live-Unload 主路径；不依赖 ServerLevel）。
     * <p>
     * 通过构造 {@link net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket}
     * 间接获取 packet 字节，再委托 {@link #packetBytesToNbt}。避免直接调用依赖服务端的
     * {@code ChunkSerializer.write}。
     *
     * @param chunk 目标区块
     * @param level 客户端世界
     * @return chunk NBT；失败返回 null
     */
    public static CompoundTag levelChunkToNbt(LevelChunk chunk, ClientLevel level) {
        if (chunk == null || level == null) return null;
        try {
            // 光照剥离与服务端配置一致（空光照，apply 时原版重算）
            BitSet emptyMask = new BitSet();
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
                    new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                            chunk, level.getLightEngine(), emptyMask, emptyMask);

            byte[] packetBytes;
#if MC_VER < MC_1_20_5
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                packet.write(buf);
                packetBytes = new byte[buf.readableBytes()];
                buf.readBytes(packetBytes);
            } finally {
                buf.release();
            }
#else
            net.minecraft.network.RegistryFriendlyByteBuf buf =
                    new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                            level.registryAccess());
            try {
                net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, packet);
                packetBytes = new byte[buf.readableBytes()];
                buf.readBytes(packetBytes);
            } finally {
                buf.release();
            }
#endif

            CompoundTag nbt = packetBytesToNbt(packetBytes, level.registryAccess());
            // y_pos 不写入：apply 走 packet 重组路径不需要；导出时由 level.dat 兜底
            return nbt;
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: levelChunkToNbt failed for chunk {}", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * 从 NBT sections 计算 per-section hash（替代 packet 路径的 computeSectionHashesFromSerialized）。
     * <p>
     * 与 {@link ChunkContentHashUtil#computeSectionHashes(LevelChunk)} 对空 section（hasOnlyAir）跳过逻辑一致。
     *
     * @param nbt            chunk NBT
     * @param sectionCount   section 数量（从 NBT 或 level 取）
     * @param registryAccess 注册表访问
     * @return section 哈希数组（索引 = sectionIndex，空 section = 0）
     */
    public static long[] computeSectionHashesFromNbt(CompoundTag nbt, int sectionCount,
                                                     RegistryAccess registryAccess) {
        if (nbt == null) return new long[0];
        ListTag sectionsList = CompoundTagCompat.getList(nbt, "sections");
        int len = Math.max(sectionCount, sectionsList.size());
        long[] hashes = new long[len];
        LevelChunkSection scratch = LevelChunkSectionCompat.create(registryAccess);
        for (int i = 0; i < sectionsList.size(); i++) {
            Tag t = sectionsList.get(i);
            if (!(t instanceof CompoundTag ct)) continue;
            if (CompoundTagCompat.getBoolean(ct, "has_only_air", false)) continue;
            Tag dataTag = ct.get("data");
            if (!(dataTag instanceof ByteArrayTag bat)) continue;
            byte[] bytes = bat.getAsByteArray();
            if (bytes.length == 0) continue;
            // 从 NBT 字节读取 section，用 writeSectionForHash 哈希
            // 1.21.9+ pack(Strategy) 规范化，1.20.1-1.21.8 section.write() 字节
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
            try {
                scratch.read(buf);
                if (scratch.hasOnlyAir()) continue;  // 运行时二次校验，防止 NBT has_only_air 标志错误
                long hash = ChunkContentHashUtil.computeSectionHash(scratch);
                hashes[i] = hash == 0L ? 1L : hash;
            } finally {
                buf.release();
            }
        }
        return hashes;
    }

    /**
     * 校验缓存字节是否为合法 chunk NBT（旧 packet 缓存识别用）。
     * <p>
     * 检测策略：尝试读 NBT，顶层为 {@link CompoundTag} 且 {@code sections} 为 {@link ListTag}。
     *
     * @param bytes 解压后的缓存字节
     * @return true = 合法 NBT
     */
    public static boolean isValidChunkNbt(byte[] bytes) {
        CompoundTag nbt = bytesToNbt(bytes);
        if (nbt == null) return false;
        return CompoundTagCompat.containsList(nbt, "sections");
    }

    /**
     * 把 chunk NBT 序列化为可落盘的字节（含 magic 前缀）。
     */
    public static byte[] nbtToBytes(CompoundTag nbt) {
        if (nbt == null) return null;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buf.writeBytes(NBT_MAGIC);
            buf.writeNbt(nbt);
            byte[] result = new byte[buf.readableBytes()];
            buf.getBytes(0, result);
            return result;
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: nbtToBytes failed", e);
            return null;
        } finally {
            buf.release();
        }
    }

    /**
     * 从落盘字节反序列化为 chunk NBT（校验 magic 前缀）。
     *
     * @param bytes 缓存字节（含 magic 前缀）
     * @return chunk NBT；非 NBT 或 magic 不符返回 null
     */
    public static CompoundTag bytesToNbt(byte[] bytes) {
        if (bytes == null || bytes.length < NBT_MAGIC.length + 4) return null;
        // magic 前缀校验
        for (int i = 0; i < NBT_MAGIC.length; i++) {
            if (bytes[i] != NBT_MAGIC[i]) return null;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        try {
            buf.readerIndex(NBT_MAGIC.length);
            Tag tag = buf.readNbt();
            return tag instanceof CompoundTag ct ? ct : null;
        } catch (Exception e) {
            return null;
        } finally {
            buf.release();
        }
    }

    // ===== 内部辅助 =====

    private static CompoundTag buildChunkNbt(int chunkX, int chunkZ, int sectionCount,
                                             ListTag sections, CompoundTag heightmaps,
                                             ListTag blockEntities) {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("x", chunkX);
        nbt.putInt("z", chunkZ);
        nbt.putInt("section_count", sectionCount);
        nbt.put("sections", sections);
        nbt.put("heightmaps", heightmaps != null ? heightmaps : new CompoundTag());
        nbt.put("block_entities", blockEntities != null ? blockEntities : new ListTag());
        nbt.putByte("is_light_on", (byte) 0);
        return nbt;
    }

    /** 估算 section 数量：用 scratch.read 逐个解析直到字节耗尽。 */
    private static int guessSectionCount(byte[] sectionsBytes, RegistryAccess registryAccess) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(sectionsBytes));
        int count = 0;
        try {
            LevelChunkSection scratch = LevelChunkSectionCompat.create(registryAccess);
            while (buf.readableBytes() > 0) {
                try {
                    scratch.read(buf);
                    count++;
                } catch (Exception e) {
                    break;
                }
            }
        } finally {
            buf.release();
        }
        return count;
    }

    /** 把 sections 字节解析为 ListTag&lt;CompoundTag&gt;，每个 section 包成 {"data": ByteArrayTag, "has_only_air": ByteTag}。 */
    private static ListTag parseSectionsToList(byte[] sectionsBytes, int sectionCount,
                                               RegistryAccess registryAccess) {
        ListTag list = new ListTag();
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(sectionsBytes));
        try {
            LevelChunkSection scratch = LevelChunkSectionCompat.create(registryAccess);
            for (int i = 0; i < sectionCount && buf.readableBytes() > 0; i++) {
                int start = buf.readerIndex();
                scratch.read(buf);
                int end = buf.readerIndex();
                int len = end - start;
                byte[] sectionBytes = new byte[len];
                System.arraycopy(sectionsBytes, start, sectionBytes, 0, len);

                CompoundTag sectionTag = new CompoundTag();
                sectionTag.putBoolean("has_only_air", scratch.hasOnlyAir());
                sectionTag.putByteArray("data", sectionBytes);
                list.add(sectionTag);
            }
        } finally {
            buf.release();
        }
        return list;
    }

    /**
     * 剥离 NBT 字节的 magic 前缀，返回纯 NBT 二进制（供导出原版 Anvil 用）。
     * <p>
     * 与 {@link #bytesToNbt} 不同：不解析 NBT，仅做字节切片。
     *
     * @param bytes NBT 字节（含 magic 前缀）
     * @return 纯 NBT 字节；magic 不符返回 null
     */
    public static byte[] stripMagicPrefix(byte[] bytes) {
        if (bytes == null || bytes.length < NBT_MAGIC.length) return null;
        for (int i = 0; i < NBT_MAGIC.length; i++) {
            if (bytes[i] != NBT_MAGIC[i]) return null;
        }
        byte[] result = new byte[bytes.length - NBT_MAGIC.length];
        System.arraycopy(bytes, NBT_MAGIC.length, result, 0, result.length);
        return result;
    }

    /**
     * 检测字节是否为 NBT 格式（含 magic 前缀）；若是则转为 packet 字节，否则原样返回。
     * <p>
     * 供 {@code applyChunkData} 同时支持 NBT 字节（新缓存）与 packet 字节（旧缓存向后兼容）。
     *
     * @param data           缓存字节（NBT 或 packet）
     * @param registryAccess 注册表访问
     * @return packet 字节（用于 applyToLevelFromByteBuf）
     */
    public static byte[] maybeNbtToPacketBytes(byte[] data, RegistryAccess registryAccess) {
        return maybeNbtToPacketBytes(data, registryAccess, -1);
    }

    public static byte[] maybeNbtToPacketBytes(byte[] data, RegistryAccess registryAccess,
                                              int expectedSectionCount) {
        if (data == null || data.length < NBT_MAGIC.length) return data;
        // magic 前缀校验
        boolean isNbt = true;
        for (int i = 0; i < NBT_MAGIC.length; i++) {
            if (data[i] != NBT_MAGIC[i]) { isNbt = false; break; }
        }
        if (!isNbt) return data; // 旧 packet 字节，原样返回
        CompoundTag nbt = bytesToNbt(data);
        if (nbt == null) return data; // 解析失败，原样返回（apply 会失败但保留诊断）
        byte[] packetBytes = nbtToPacketBytes(nbt, registryAccess, expectedSectionCount);
        return packetBytes != null ? packetBytes : data;
    }
}
