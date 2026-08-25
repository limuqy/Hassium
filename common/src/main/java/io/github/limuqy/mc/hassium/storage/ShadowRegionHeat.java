package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * 影子端 region 文件级热度索引（{@code heat.idx}）。
 * <p>
 * 条目按 {@code r.X.Z.mca}（维度 + region 坐标）计，不按单柱。落盘格式可解析：
 * 魔数 + 版本 + 条数 + 每条（复合键 / 访问次数 / 上次访问 / 文件字节）。
 * 容量扫描只列目录 + {@link Files#size}，不拆 Anvil 头。
 */
public final class ShadowRegionHeat {

    /** heat.idx 魔数（"HSH2"）与版本；v1 逐柱索引直接丢弃。 */
    private static final int HEAT_MAGIC = 0x48534832;
    private static final int HEAT_VERSION = 2;
    private static final int HEAT_MAX_ENTRIES = 100_000;

    private static final ConcurrentHashMap<Long, HotEntry> HEAT = new ConcurrentHashMap<>();

    public record HotEntry(int accessCount, long lastAccessMillis, long sizeBytes) {}

    /** 扫描结果：一个磁盘上的 .mca。 */
    public record RegionFileStat(String dimension, int regionX, int regionZ,
                                 long sizeBytes, HotEntry hot) {}

    private ShadowRegionHeat() {}

    public static long heatKey(String dimension, int regionX, int regionZ) {
        return DimensionKey.key(dimension, regionX, regionZ);
    }

    public static long heatKeyForChunk(String dimension, int chunkX, int chunkZ) {
        return heatKey(dimension, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
    }

    /** 记录一次访问（注入 / 读盘命中）；落到所属 region 文件。 */
    public static void recordAccess(String dimension, ChunkPos pos) {
        long key = heatKeyForChunk(dimension, pos.x, pos.z);
        long now = System.currentTimeMillis();
        HEAT.compute(key, (k, h) -> new HotEntry(
                h == null ? 1 : h.accessCount + 1,
                now,
                h == null ? 0L : h.sizeBytes));
    }

    public static void recordAccess(ChunkPos pos) {
        recordAccess(DimensionKey.OVERWORLD, pos);
    }

    /** 落盘后回写该 region 文件大小；不增加访问计数、不刷新 lastAccess。 */
    public static void updateRegionSize(String dimension, int regionX, int regionZ, long sizeBytes) {
        long key = heatKey(dimension, regionX, regionZ);
        HEAT.compute(key, (k, h) -> {
            if (h == null) {
                return new HotEntry(0, 0L, sizeBytes);
            }
            return new HotEntry(h.accessCount, h.lastAccessMillis, sizeBytes);
        });
    }

    public static void removeRegion(String dimension, int regionX, int regionZ) {
        HEAT.remove(heatKey(dimension, regionX, regionZ));
    }

    /** 删除区块所属 region 的热度（整文件淘汰时用）。 */
    public static void remove(String dimension, ChunkPos pos) {
        removeRegion(dimension, Math.floorDiv(pos.x, 32), Math.floorDiv(pos.z, 32));
    }

    public static void remove(ChunkPos pos) {
        remove(DimensionKey.OVERWORLD, pos);
    }

    public static void load(Path worldRoot) {
        HEAT.clear();
        Path file = worldRoot.resolve("heat.idx");
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != HEAT_MAGIC || in.readInt() != HEAT_VERSION) {
                Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx version mismatch, reset");
                return;
            }
            int count = in.readInt();
            if (count < 0 || count > HEAT_MAX_ENTRIES) {
                Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx corrupt count={}, reset", count);
                return;
            }
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                int accessCount = in.readInt();
                long lastAccessMillis = in.readLong();
                long sizeBytes = in.readLong();
                HEAT.put(key, new HotEntry(accessCount, lastAccessMillis, sizeBytes));
            }
            Constants.LOG.info("Hassium: [SHADOW_CLEANUP] heat.idx loaded {} region files", HEAT.size());
        } catch (EOFException e) {
            Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx truncated, reset");
            HEAT.clear();
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx load failed, reset", e);
            HEAT.clear();
        }
    }

    public static void save(Path worldRoot) {
        Path file = worldRoot.resolve("heat.idx");
        if (HEAT.isEmpty()) {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(worldRoot.resolve("heat.idx.tmp"));
            } catch (IOException e) {
                Constants.LOG.debug("Hassium: [SHADOW_CLEANUP] heat.idx delete failed", e);
            }
            return;
        }
        try {
            Path tmp = worldRoot.resolve("heat.idx.tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(HEAT_MAGIC);
                out.writeInt(HEAT_VERSION);
                out.writeInt(HEAT.size());
                for (var e : HEAT.entrySet()) {
                    HotEntry h = e.getValue();
                    out.writeLong(e.getKey());
                    out.writeInt(h.accessCount);
                    out.writeLong(h.lastAccessMillis);
                    out.writeLong(h.sizeBytes);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: [SHADOW_CLEANUP] heat.idx save failed", e);
        }
    }

    public static void reset() {
        HEAT.clear();
    }

    public static int accessCountOf(String dimension, ChunkPos pos) {
        HotEntry h = HEAT.get(heatKeyForChunk(dimension, pos.x, pos.z));
        return h == null ? 0 : h.accessCount();
    }

    public static int accessCountOf(ChunkPos pos) {
        return accessCountOf(DimensionKey.OVERWORLD, pos);
    }

    public static int entryCount() {
        return HEAT.size();
    }

    public static HotEntry get(String dimension, int regionX, int regionZ) {
        return HEAT.get(heatKey(dimension, regionX, regionZ));
    }

    /**
     * 列出目录里的 {@code *.mca}：只解析文件名 + {@link Files#size}，不读 Anvil 头。
     *
     * @param liveRegionKeys 本会话占用的 region（{@link ChunkPos#asLong(int, int)}），这些不算淘汰候选但仍计入容量
     */
    public static long collectRegionFiles(Path regionDir, String dimension,
                                          Set<Long> liveRegionKeys, List<RegionFileStat> out) {
        if (regionDir == null || !Files.isDirectory(regionDir)) {
            return 0L;
        }
        long total = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path file : stream) {
                Long regionKey = RegionCache.regionKeyFromFileName(file.getFileName().toString());
                if (regionKey == null) {
                    continue;
                }
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException e) {
                    continue;
                }
                total += size;
                int rx = RegionCache.regionXOf(regionKey);
                int rz = RegionCache.regionZOf(regionKey);
                updateRegionSize(dimension, rx, rz, size);
                if (out != null && (liveRegionKeys == null || !liveRegionKeys.contains(regionKey))) {
                    out.add(new RegionFileStat(dimension, rx, rz, size, get(dimension, rx, rz)));
                }
            }
        } catch (IOException e) {
            Constants.LOG.debug("Hassium: [SHADOW_CLEANUP] region dir list failed {}", regionDir, e);
        }
        return total;
    }

    /** 测试用：收集全部文件为候选（无 live 跳过）。 */
    public static List<RegionFileStat> collectRegionFiles(Path regionDir, String dimension) {
        List<RegionFileStat> out = new ArrayList<>();
        collectRegionFiles(regionDir, dimension, Set.of(), out);
        return out;
    }
}
