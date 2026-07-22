package io.github.limuqy.mc.hassium.cache.client;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.compression.DictionaryRegistry;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.storage.HassiumRegionFile;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端区块缓存存储层
 * <p>
 * 使用统一的 HassiumRegionFile 存储区块数据。
 * 命中比对走 Region MetadataTable 的 contentHash。
 * 热度索引（{@link ClientHeatIndex}）与 section 哈希（{@link SectionHashStore}）分离存储。
 * <p>
 * 目录结构：
 * - 配置：config/hassium/hassium-client.toml / config/hassium/hassium-common.toml
 * - 热度索引：config/hassium/heat.idx
 * - 缓存文件：hassium_cache/&lt;serverId&gt;/&lt;dimension&gt;/r.&lt;x&gt;.&lt;z&gt;.mca
 * - section 哈希：hassium_cache/&lt;serverId&gt;/&lt;dimension&gt;/section_hashes.bin
 */
public class ClientHassiumStorage {

    private static final String CACHE_DIR_NAME = "hassium_cache";
    private static final String CONFIG_DIR_NAME = "hassium";
    private static final String REGION_EXTENSION = ".mca";

    private final Path cacheRoot;
    private final String serverId;
    private final String dimension;
    private final Map<Long, HassiumRegionFile> openRegions = new ConcurrentHashMap<>();

    private final ChunkBloomFilter bloomFilter;

    private static ClientHeatIndex sharedHeatIndex;
    private SectionHashStore sectionHashStore;
    private CacheEvictionManager evictionManager;
    private long lastCleanupTick = 0;

    public ClientHassiumStorage(Path gameDir, String serverId, String dimension) {
        this.serverId = serverId;
        this.dimension = dimension;
        this.cacheRoot = gameDir.resolve(CACHE_DIR_NAME).resolve(serverId).resolve(dimension);

        this.bloomFilter = ChunkBloomFilter.createDefault();

        initializeStorage(gameDir);
        loadExistingCacheToBloomFilter();
    }

    private void loadExistingCacheToBloomFilter() {
        try {
            if (!Files.exists(cacheRoot)) {
                return;
            }

            int count = 0;
            try (var stream = Files.list(cacheRoot)) {
                for (Path file : stream.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(REGION_EXTENSION)) {
                        try {
                            String fileName = file.getFileName().toString();
                            String[] parts = fileName.split("\\.");
                            if (parts.length >= 4) {
                                int rx = Integer.parseInt(parts[1]);
                                int rz = Integer.parseInt(parts[2]);

                                HassiumRegionFile region = new HassiumRegionFile(file);
                                for (int x = 0; x < 32; x++) {
                                    for (int z = 0; z < 32; z++) {
                                        int chunkX = (rx << 5) + x;
                                        int chunkZ = (rz << 5) + z;
                                        if (region.hasChunk(new ChunkPos(chunkX, chunkZ))) {
                                            bloomFilter.put(chunkX, chunkZ, dimension);
                                            count++;
                                        }
                                    }
                                }
                                region.close();
                            }
                        } catch (Exception e) {
                            Constants.LOG.debug("Failed to scan region file {} for Bloom Filter", file, e);
                        }
                    }
                }
            }

            if (count > 0) {
                Constants.LOG.info("Hassium: Loaded {} cached chunks into Bloom Filter for {} ({})",
                        count, serverId, dimension);
            }
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Failed to load existing cache into Bloom Filter", e);
        }
    }

    private void initializeStorage(Path gameDir) {
        try {
            Files.createDirectories(cacheRoot);

            synchronized (ClientHassiumStorage.class) {
                if (sharedHeatIndex == null) {
                    Path configDir = gameDir.resolve("config").resolve(CONFIG_DIR_NAME);
                    Files.createDirectories(configDir);
                    sharedHeatIndex = new ClientHeatIndex(configDir);
                    Constants.LOG.info("Hassium: Shared heat index initialized at {}", configDir);
                }
            }

            sectionHashStore = new SectionHashStore(cacheRoot);
            evictionManager = new CacheEvictionManager(sharedHeatIndex, this);
            Constants.LOG.info("Hassium: Client cache storage initialized for server {} dimension {}",
                    serverId, dimension);
        } catch (IOException e) {
            Constants.LOG.error("Hassium: Failed to initialize cache storage for server {} dimension {}",
                    serverId, dimension, e);
            evictionManager = null;
            sectionHashStore = null;
        }
    }

    /**
     * 持久化区块数据到缓存。
     * <p>
     * 自 {@code disk-nbt-cache-and-export} 起，{@code nbtData} 语义为 {@link ChunkDiskCodec#nbtToBytes}
     * 产出的 NBT 字节（含 {@code "HBT1"} magic 前缀）；ZSTD 字典压缩 + type 126 + MetadataTable 逻辑不变。
     *
     * @param pos           区块坐标
     * @param nbtData       NBT 字节（含 magic 前缀，未压缩）
     * @param contentHash   内容哈希（必须等于 combineSectionHashesFromArray(sectionHashes)）
     * @param sectionHashes per-section 哈希数组（可为 null）
     * @return 是否成功
     */
    public boolean persist(ChunkPos pos, byte[] nbtData, long contentHash, long[] sectionHashes) {
        try {
            int level = HassiumConfigService.getInstance().getCacheCompressionLevel();
            byte[] compressed = compressWithDictionary(nbtData, level);

            byte[] chunkData = new byte[1 + compressed.length];
            chunkData[0] = (byte) 126;
            System.arraycopy(compressed, 0, chunkData, 1, compressed.length);

            HassiumRegionFile region = getRegionFile(pos);
            region.writeChunk(pos, chunkData, contentHash);

            updateIndexEntries(pos, chunkData.length, sectionHashes);

            bloomFilter.put(pos.x, pos.z, dimension);

            Constants.LOG.debug("Cached chunk [{}, {}] to region ({} bytes, contentHash={})",
                    pos.x, pos.z, chunkData.length, Long.toHexString(contentHash));
            return true;

        } catch (Exception e) {
            Constants.LOG.error("Failed to persist chunk [{}, {}] to cache", pos.x, pos.z, e);
            return false;
        }
    }

    private void updateIndexEntries(ChunkPos pos, int chunkBytes, long[] sectionHashes) {
        if (sharedHeatIndex != null) {
            try {
                long nowTicks = System.currentTimeMillis() / 50;
                ClientHeatIndex.CacheEntryInfo entry = new ClientHeatIndex.CacheEntryInfo(
                        serverId,
                        pos.x,
                        pos.z,
                        dimension,
                        pos.x >> 5,
                        pos.z >> 5,
                        1,
                        nowTicks,
                        chunkBytes,
                        0.0
                );
                sharedHeatIndex.upsert(entry);
            } catch (Exception e) {
                Constants.LOG.warn("Failed to update heat index for chunk [{}, {}]", pos.x, pos.z, e);
            }
        }

        if (sectionHashStore != null && sectionHashes != null) {
            try {
                sectionHashStore.put(pos.x, pos.z, sectionHashes);
            } catch (Exception e) {
                Constants.LOG.warn("Failed to update section hashes for chunk [{}, {}]", pos.x, pos.z, e);
            }
        }
    }

    /**
     * 快速读取区块元数据（只读 Metadata Table）
     */
    public ClientChunkMetadata readMetadata(ChunkPos pos) {
        if (!bloomFilter.mightContain(pos.x, pos.z, dimension)) {
            return null;
        }

        try {
            HassiumRegionFile region = getRegionFileOrNull(pos);
            if (region == null || !region.hasChunk(pos)) {
                return null;
            }

            long contentHash = region.readContentHash(pos);
            if (contentHash == 0L) {
                return null;
            }

            return new ClientChunkMetadata(pos.x, pos.z, contentHash);
        } catch (Exception e) {
            Constants.LOG.warn("Failed to read cache metadata for chunk [{}, {}]", pos.x, pos.z, e);
            return null;
        }
    }

    /**
     * 读取 chunk 级哈希（阶段一比对用）
     * <p>
     * 优先用 MetadataTable 的 contentHash（与服务端 chunkHash 同为 combine(sectionHashes)）。
     * 若 MetadataTable 为空/无效但 SectionHashStore 有数据，则 combine 回退（兼容曾写入 0→1 的旧缓存）。
     */
    public long readChunkHash(ChunkPos pos) {
        if (!bloomFilter.mightContain(pos.x, pos.z, dimension)) {
            return 0L;
        }
        try {
            HassiumRegionFile region = getRegionFileOrNull(pos);
            if (region == null || !region.hasChunk(pos)) {
                return 0L;
            }
            long metaHash = region.readContentHash(pos);
            // 0 表示无；1 多为历史误写（pending contentHash 恒为 0 时被翻成 1），不可信
            if (metaHash != 0L && metaHash != 1L) {
                return metaHash;
            }
            long[] sectionHashes = readSectionHashes(pos);
            if (sectionHashes != null && sectionHashes.length > 0) {
                return ChunkContentHashUtil.combineSectionHashesFromArray(sectionHashes);
            }
            return metaHash;
        } catch (Exception e) {
            Constants.LOG.debug("Failed to read chunk hash for [{}, {}]", pos.x, pos.z, e);
            return 0L;
        }
    }

    /**
     * 读取 per-section 哈希数组（阶段二比对用）
     */
    public long[] readSectionHashes(ChunkPos pos) {
        if (sectionHashStore == null) {
            return null;
        }
        try {
            return sectionHashStore.get(pos.x, pos.z);
        } catch (Exception e) {
            Constants.LOG.debug("Failed to read section hashes for chunk [{}, {}]", pos.x, pos.z, e);
            return null;
        }
    }

    /**
     * 加载并解压区块数据
     */
    public byte[] loadAndDecompress(ChunkPos pos) {
        try {
            HassiumRegionFile region = getRegionFileOrNull(pos);
            if (region == null || !region.hasChunk(pos)) {
                return null;
            }

            byte[] chunkData = region.readChunk(pos);
            if (chunkData == null || chunkData.length < 2) {
                return null;
            }

            byte compressionType = chunkData[0];
            if (compressionType != 126) {
                Constants.LOG.warn("Unknown compression type {} for cached chunk [{}, {}]", compressionType, pos.x, pos.z);
                return null;
            }

            byte[] compressedData = new byte[chunkData.length - 1];
            System.arraycopy(chunkData, 1, compressedData, 0, compressedData.length);

            updateAccessInfo(pos);

            return decompressWithDictionary(compressedData);

        } catch (Exception e) {
            Constants.LOG.error("Failed to load cached chunk [{}, {}]", pos.x, pos.z, e);
            return null;
        }
    }

    private void updateAccessInfo(ChunkPos pos) {
        if (sharedHeatIndex == null) {
            return;
        }
        try {
            long currentGameTime = System.currentTimeMillis() / 50;
            sharedHeatIndex.updateAccess(serverId, pos.x, pos.z, dimension, currentGameTime);
        } catch (Exception e) {
            Constants.LOG.debug("Failed to update access info for chunk [{}, {}]", pos.x, pos.z, e);
        }
    }

    public boolean exists(ChunkPos pos) {
        try {
            HassiumRegionFile region = getRegionFileOrNull(pos);
            return region != null && region.hasChunk(pos);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除缓存：Region 内单块 + section 哈希 + 热度条目。
     */
    public boolean remove(ChunkPos pos) {
        boolean removed = false;
        try {
            HassiumRegionFile region = getRegionFileOrNull(pos);
            if (region != null && region.hasChunk(pos)) {
                region.deleteChunk(pos);
                removed = true;
            }

            if (sectionHashStore != null) {
                sectionHashStore.remove(pos.x, pos.z);
            }

            if (sharedHeatIndex != null) {
                sharedHeatIndex.deleteEntry(serverId, pos.x, pos.z, dimension);
            }

            return removed;
        } catch (Exception e) {
            Constants.LOG.error("Failed to delete cache for chunk [{}, {}]", pos.x, pos.z, e);
            return false;
        }
    }

    /**
     * 关闭本维度打开的 region 与 section 哈希存储。
     * <p>
     * 不关闭全局热度索引（其他维度实例可能仍在使用）。
     */
    public void close() {
        if (sectionHashStore != null) {
            try {
                sectionHashStore.close();
            } catch (Exception e) {
                Constants.LOG.warn("Failed to close section hash store", e);
            }
            sectionHashStore = null;
        }

        for (HassiumRegionFile region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                Constants.LOG.warn("Failed to close region file", e);
            }
        }
        openRegions.clear();
    }

    /**
     * 关闭共享热度索引（断连 / 退出时调用）
     */
    public static void closeSharedDatabase() {
        closeSharedHeatIndex();
    }

    public static void closeSharedHeatIndex() {
        synchronized (ClientHassiumStorage.class) {
            if (sharedHeatIndex != null) {
                try {
                    sharedHeatIndex.close();
                } catch (IOException e) {
                    Constants.LOG.warn("Failed to close shared heat index", e);
                }
                sharedHeatIndex = null;
            }
        }
    }

    public int clearAll() {
        close();
        int count = 0;
        try {
            if (Files.exists(cacheRoot)) {
                try (var stream = Files.list(cacheRoot)) {
                    for (Path file : stream.toList()) {
                        if (Files.isRegularFile(file) && (file.toString().endsWith(REGION_EXTENSION)
                                || file.getFileName().toString().equals("section_hashes.bin"))) {
                            Files.delete(file);
                            count++;
                        }
                    }
                }
            }
            if (sharedHeatIndex != null) {
                sharedHeatIndex.deleteByServerAndDimension(serverId, dimension);
            }
        } catch (IOException e) {
            Constants.LOG.error("Failed to clear cache for server {} dimension {}", serverId, dimension, e);
        }
        return count;
    }

    public int performCacheCleanup(long currentGameTime) {
        if (evictionManager == null) {
            return 0;
        }

        HassiumConfigService configService = HassiumConfigService.getInstance();
        int cleanupInterval = configService.getCleanupIntervalTicks();

        if (currentGameTime - lastCleanupTick < cleanupInterval) {
            return 0;
        }

        lastCleanupTick = currentGameTime;
        return evictionManager.performCleanup(currentGameTime, configService.getConfig().clientCache());
    }

    public CacheEvictionManager.HotStats getHotStats(long currentGameTime) {
        if (evictionManager == null) {
            return new CacheEvictionManager.HotStats(0, 0, 0, 0, 0);
        }
        return evictionManager.getHotStats(currentGameTime);
    }

    public int manualCleanup(long currentGameTime) {
        if (evictionManager == null) {
            return 0;
        }
        lastCleanupTick = currentGameTime;
        return evictionManager.performCleanup(currentGameTime,
                HassiumConfigService.getInstance().getConfig().clientCache());
    }

    public int clearDimension(String dimension) {
        if (evictionManager == null) {
            return 0;
        }
        return evictionManager.clearDimension(dimension);
    }

    public ClientHeatIndex getHeatIndex() {
        return sharedHeatIndex;
    }

    public String getServerId() {
        return serverId;
    }

    public String getDimension() {
        return dimension;
    }

    public Path getCacheRoot() {
        return cacheRoot;
    }

    private HassiumRegionFile getRegionFile(ChunkPos pos) throws IOException {
        long regionKey = regionKey(pos.x >> 5, pos.z >> 5);
        try {
            return openRegions.computeIfAbsent(regionKey, key -> {
                try {
                    Files.createDirectories(cacheRoot);
                    Path regionPath = cacheRoot.resolve("r." + (pos.x >> 5) + "." + (pos.z >> 5) + REGION_EXTENSION);
                    return new HassiumRegionFile(regionPath);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        }
    }

    private HassiumRegionFile getRegionFileOrNull(ChunkPos pos) {
        long regionKey = regionKey(pos.x >> 5, pos.z >> 5);
        HassiumRegionFile region = openRegions.get(regionKey);
        if (region != null) {
            return region;
        }
        Path regionPath = cacheRoot.resolve("r." + (pos.x >> 5) + "." + (pos.z >> 5) + REGION_EXTENSION);
        if (!Files.exists(regionPath)) {
            return null;
        }
        try {
            return openRegions.computeIfAbsent(regionKey, key -> {
                try {
                    return new HassiumRegionFile(regionPath);
                } catch (IOException e) {
                    Constants.LOG.warn("Failed to open region file {}", regionPath, e);
                    return null;
                }
            });
        } catch (Exception e) {
            Constants.LOG.warn("Failed to open region file {}", regionPath, e);
            return null;
        }
    }

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    private byte[] compressWithDictionary(byte[] data, int level) throws Exception {
        CompressionService service = CompressionService.getInstance();
        DictionaryRegistry registry = service.getDictionaryRegistry()
                .orElseThrow(() -> new RuntimeException("Dictionary registry not available"));

        String dictionaryId = Constants.DEFAULT_ZSTD_DICTIONARY_ID;
        byte[] dictionary = registry.findDictionary(dictionaryId)
                .orElseThrow(() -> new RuntimeException("Dictionary not found: " + dictionaryId));

        ZstdDictCompress dict = new ZstdDictCompress(dictionary, level);
        return Zstd.compress(data, dict);
    }

    /** 供 {@link CacheWorldExporter} 复用的 ZSTD 字典解压入口。 */
    public byte[] decompressForExport(byte[] compressedData) {
        return decompressWithDictionary(compressedData);
    }

    private byte[] decompressWithDictionary(byte[] compressedData) {
        CompressionService service = CompressionService.getInstance();
        DictionaryRegistry registry = service.getDictionaryRegistry()
                .orElseThrow(() -> new RuntimeException("Dictionary registry not available"));

        String dictionaryId = Constants.DEFAULT_ZSTD_DICTIONARY_ID;
        byte[] dictionary = registry.findDictionary(dictionaryId)
                .orElseThrow(() -> new RuntimeException("Dictionary not found: " + dictionaryId));

        ZstdDictDecompress dict = new ZstdDictDecompress(dictionary);
        int decompressedSize = (int) Zstd.decompressedSize(compressedData);
        if (decompressedSize <= 0) {
            decompressedSize = compressedData.length * 4;
        }
        byte[] result = new byte[decompressedSize];
        long actualSize = Zstd.decompressFastDict(result, 0, compressedData, 0, compressedData.length, dict);
        if (actualSize <= 0) {
            throw new RuntimeException("ZSTD dictionary decompression failed: invalid output");
        }
        if (actualSize < decompressedSize) {
            byte[] trimmed = new byte[(int) actualSize];
            System.arraycopy(result, 0, trimmed, 0, (int) actualSize);
            return trimmed;
        }
        return result;
    }
}
