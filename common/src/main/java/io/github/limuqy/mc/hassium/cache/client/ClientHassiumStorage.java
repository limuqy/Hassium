package io.github.limuqy.mc.hassium.cache.client;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.compression.DictionaryRegistry;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.storage.HassiumRegionFile;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端区块缓存存储层
 * <p>
 * 使用统一的 HassiumRegionFile 存储区块数据。
 * 支持快速元数据读取（通过 Metadata Table）。
 * 集成 SQLite 数据库进行热度追踪和缓存管理。
 * <p>
 * 性能优化：数据库操作异步批量处理，避免阻塞主线程。
 * <p>
 * 目录结构：
 * - 配置文件：config/hassium/hassium.json
 * - 数据库：config/hassium/hassium_cache.db
 * - 缓存文件：hassium_cache/<serverId>/<dimension>/r.<x>.<z>.mca
 */
public class ClientHassiumStorage {

    private static final String CACHE_DIR_NAME = "hassium_cache";
    private static final String CONFIG_DIR_NAME = "hassium";
    private static final String REGION_EXTENSION = ".mca";
    private static final int DEFAULT_COMPRESSION_LEVEL = 9;

    private final Path cacheRoot;
    private final String serverId;
    private final String dimension;
    private final Map<Long, HassiumRegionFile> openRegions = new ConcurrentHashMap<>();

    // Bloom Filter 用于快速预筛，减少无效的 .mca 文件读取
    private final ChunkBloomFilter bloomFilter;

    // SQLite 数据库和清理管理器
    private static ClientCacheDatabase sharedDatabase; // 全局共享数据库
    private CacheEvictionManager evictionManager;
    private long lastCleanupTick = 0;

    /**
     * 构造函数
     *
     * @param gameDir     游戏目录
     * @param serverId    服务器标识（如 server_127.0.0.1_25565）
     * @param dimension   维度标识（如 minecraft_overworld）
     */
    public ClientHassiumStorage(Path gameDir, String serverId, String dimension) {
        this.serverId = serverId;
        this.dimension = dimension;
        // 缓存目录：hassium_cache/<serverId>/<dimension>
        this.cacheRoot = gameDir.resolve(CACHE_DIR_NAME).resolve(serverId).resolve(dimension);

        // 初始化 Bloom Filter
        boolean bloomEnabled = HassiumConfigService.getInstance().isBloomFilterEnabled();
        this.bloomFilter = bloomEnabled ? ChunkBloomFilter.fromConfig() : null;

        initializeDatabase(gameDir);

        // 加载现有缓存到 Bloom Filter
        if (bloomFilter != null) {
            loadExistingCacheToBloomFilter();
        }
    }

    /**
     * 加载现有缓存到 Bloom Filter
     */
    private void loadExistingCacheToBloomFilter() {
        if (bloomFilter == null) {
            return;
        }

        try {
            if (!Files.exists(cacheRoot)) {
                return;
            }

            int count = 0;
            try (var stream = Files.list(cacheRoot)) {
                for (Path file : stream.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(REGION_EXTENSION)) {
                        try {
                            // 从文件名解析 region 坐标：r.<rx>.<rz>.mca
                            String fileName = file.getFileName().toString();
                            String[] parts = fileName.split("\\.");
                            if (parts.length >= 4) {
                                int rx = Integer.parseInt(parts[1]);
                                int rz = Integer.parseInt(parts[2]);

                                HassiumRegionFile region = new HassiumRegionFile(file);
                                // 扫描 region 中的所有区块
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

    /**
     * 初始化数据库
     */
    private void initializeDatabase(Path gameDir) {
        try {
            Files.createDirectories(cacheRoot);

            // 使用全局共享数据库（位于 config/hassium/ 目录）
            synchronized (ClientHassiumStorage.class) {
                if (sharedDatabase == null) {
                    Path configDir = gameDir.resolve("config").resolve(CONFIG_DIR_NAME);
                    Files.createDirectories(configDir);
                    sharedDatabase = new ClientCacheDatabase(configDir);
                    Constants.LOG.info("Hassium: Shared cache database initialized at {}", configDir);
                }
            }

            evictionManager = new CacheEvictionManager(sharedDatabase, this);
            Constants.LOG.info("Hassium: Client cache storage initialized for server {} dimension {}",
                    serverId, dimension);
        } catch (IOException | SQLException e) {
            Constants.LOG.error("Hassium: Failed to initialize cache storage for server {} dimension {}",
                    serverId, dimension, e);
            evictionManager = null;
        }
    }

    /**
     * 持久化区块数据到缓存
     *
     * @param pos           区块坐标
     * @param nbtData       原始数据（未压缩）
     * @param contentHash   内容哈希（来自服务端元数据或本地重算）
     * @param sectionHashes per-section 哈希数组（可为 null）
     * @return 是否成功
     */
    public boolean persist(ChunkPos pos, byte[] nbtData, long contentHash, long[] sectionHashes) {
        try {
            byte[] compressed = compressWithDictionary(nbtData, DEFAULT_COMPRESSION_LEVEL);

            byte[] chunkData = new byte[1 + compressed.length];
            chunkData[0] = (byte) 126;
            System.arraycopy(compressed, 0, chunkData, 1, compressed.length);

            HassiumRegionFile region = getRegionFile(pos);
            region.writeChunk(pos, chunkData, contentHash);

            updateDatabaseEntry(pos, contentHash, chunkData.length, sectionHashes);

            // 添加到 Bloom Filter
            if (bloomFilter != null) {
                bloomFilter.put(pos.x, pos.z, dimension);
            }

            Constants.LOG.debug("Cached chunk [{}, {}] to region ({} bytes, contentHash={})",
                    pos.x, pos.z, chunkData.length, Long.toHexString(contentHash));
            return true;

        } catch (Exception e) {
            Constants.LOG.error("Failed to persist chunk [{}, {}] to cache", pos.x, pos.z, e);
            return false;
        }
    }

    private void updateDatabaseEntry(ChunkPos pos, long contentHash, int dataSize, long[] sectionHashes) {
        if (sharedDatabase == null) {
            return;
        }

        try {
            String regionFileName = "r." + (pos.x >> 5) + "." + (pos.z >> 5) + REGION_EXTENSION;
            Path regionPath = cacheRoot.resolve(regionFileName);
            long fileSize = Files.exists(regionPath) ? Files.size(regionPath) : 0;
            String relativePath = cacheRoot.relativize(regionPath).toString();
            long now = System.currentTimeMillis();

            byte[] sectionHashesBlob = serializeSectionHashes(sectionHashes);

            ClientCacheDatabase.CacheEntryInfo entry = new ClientCacheDatabase.CacheEntryInfo(
                    serverId,
                    pos.x,
                    pos.z,
                    dimension,
                    pos.x >> 5,
                    pos.z >> 5,
                    contentHash,
                    1,
                    now,
                    relativePath,
                    fileSize,
                    0.0,
                    sectionHashesBlob
            );

            sharedDatabase.upsertEntry(entry);
        } catch (Exception e) {
            Constants.LOG.warn("Failed to update cache database for chunk [{}, {}]",
                    pos.x, pos.z, e);
        }
    }

    /**
     * 快速读取区块元数据（只读 Metadata Table）
     * <p>
     * 如果启用 Bloom Filter，先通过 Bloom Filter 预筛，避免无效的 .mca 文件读取。
     */
    public ClientChunkMetadata readMetadata(ChunkPos pos) {
        // Bloom Filter 预筛：如果 Bloom Filter 表示区块不存在，直接返回 null
        if (bloomFilter != null && !bloomFilter.mightContain(pos.x, pos.z, dimension)) {
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
     * 从持久化的 sectionHashes 组合计算出 chunkHash，
     * 与服务端 {@code combineSectionHashes} 使用相同的算法，确保一致性。
     *
     * @param pos 区块坐标
     * @return chunkHash，不存在返回 0
     */
    public long readChunkHash(ChunkPos pos) {
        long[] sectionHashes = readSectionHashes(pos);
        if (sectionHashes == null || sectionHashes.length == 0) {
            return 0L;
        }
        return ChunkContentHashUtil.combineSectionHashesFromArray(sectionHashes);
    }

    /**
     * 读取 per-section 哈希数组（阶段二比对用）
     * <p>
     * 从 SQLite 数据库读取持久化的 section 哈希。
     *
     * @param pos 区块坐标
     * @return section 哈希数组，不存在返回 null
     */
    public long[] readSectionHashes(ChunkPos pos) {
        if (sharedDatabase == null) {
            return null;
        }

        try {
            byte[] blob = sharedDatabase.readSectionHashes(serverId, pos.x, pos.z, dimension);
            return deserializeSectionHashes(blob);
        } catch (Exception e) {
            Constants.LOG.debug("Failed to read section hashes for chunk [{}, {}]", pos.x, pos.z, e);
            return null;
        }
    }

    /**
     * 序列化 section 哈希数组为字节数组
     * <p>
     * 格式：count(4) + [index(4) + hash(8)] × N
     */
    private static byte[] serializeSectionHashes(long[] sectionHashes) {
        if (sectionHashes == null || sectionHashes.length == 0) {
            return null;
        }

        // 计算非零条目数
        int count = 0;
        for (long h : sectionHashes) {
            if (h != 0L) count++;
        }
        if (count == 0) return null;

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4 + count * 12);
        buf.putInt(count);
        for (int i = 0; i < sectionHashes.length; i++) {
            if (sectionHashes[i] != 0L) {
                buf.putInt(i);
                buf.putLong(sectionHashes[i]);
            }
        }
        return buf.array();
    }

    /**
     * 反序列化 section 哈希数组
     * <p>
     * 格式：count(4) + [index(4) + hash(8)] × N
     */
    private static long[] deserializeSectionHashes(byte[] blob) {
        if (blob == null || blob.length < 4) {
            return null;
        }

        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(blob);
            int count = buf.getInt();
            if (count <= 0 || count > 24 || blob.length < 4 + count * 12) {
                return null;
            }

            int maxIndex = 0;
            int savedPos = buf.position();
            for (int i = 0; i < count; i++) {
                int idx = buf.getInt();
                buf.getLong(); // skip hash
                maxIndex = Math.max(maxIndex, idx);
            }
            buf.position(savedPos);

            long[] result = new long[maxIndex + 1];
            for (int i = 0; i < count; i++) {
                int idx = buf.getInt();
                long hash = buf.getLong();
                result[idx] = hash;
            }
            return result;
        } catch (Exception e) {
            Constants.LOG.debug("Failed to deserialize section hashes", e);
            return null;
        }
    }

    /**
     * 加载并解压区块数据
     *
     * @param pos 区块坐标
     * @return 解压后的原始字节（FriendlyByteBuf 格式），不存在或解压失败返回 null
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

            // 检查压缩类型
            byte compressionType = chunkData[0];
            if (compressionType != 126) {
                Constants.LOG.warn("Unknown compression type {} for cached chunk [{}, {}]", compressionType, pos.x, pos.z);
                return null;
            }

            // 提取压缩数据（跳过第1个字节的压缩类型）
            byte[] compressedData = new byte[chunkData.length - 1];
            System.arraycopy(chunkData, 1, compressedData, 0, compressedData.length);

            // 更新访问信息
            updateAccessInfo(pos);

            // 使用 ZSTD 字典解压
            return decompressWithDictionary(compressedData);

        } catch (Exception e) {
            Constants.LOG.error("Failed to load cached chunk [{}, {}]", pos.x, pos.z, e);
            return null;
        }
    }

    /**
     * 更新访问信息（同步）
     * <p>
     * 在 loadAndDecompress 中调用，该方法在后台线程中执行。
     */
    private void updateAccessInfo(ChunkPos pos) {
        if (sharedDatabase == null) {
            return;
        }

        try {
            long currentGameTime = System.currentTimeMillis() / 50; // 转换为 ticks
            sharedDatabase.updateAccessInfo(serverId, pos.x, pos.z, dimension, currentGameTime);
        } catch (Exception e) {
            Constants.LOG.debug("Failed to update access info for chunk [{}, {}]", pos.x, pos.z, e);
        }
    }

    /**
     * 检查缓存是否存在
     */
    public boolean exists(ChunkPos pos) {
        try {
            HassiumRegionFile region = getRegionFileOrNull(pos);
            return region != null && region.hasChunk(pos);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除缓存
     */
    public boolean remove(ChunkPos pos) {
        try {
            HassiumRegionFile region = getRegionFileOrNull(pos);
            if (region != null && region.hasChunk(pos)) {
                region.deleteChunk(pos);

                // 从数据库删除
                if (sharedDatabase != null) {
                    try {
                        sharedDatabase.deleteEntry(serverId, pos.x, pos.z, dimension);
                    } catch (Exception e) {
                        Constants.LOG.debug("Failed to delete cache entry for chunk [{}, {}]", pos.x, pos.z, e);
                    }
                }

                return true;
            }
            return false;
        } catch (Exception e) {
            Constants.LOG.error("Failed to delete cache for chunk [{}, {}]", pos.x, pos.z, e);
            return false;
        }
    }

    /**
     * 关闭所有打开的 region 文件
     * <p>
     * 注意：不关闭共享数据库，因为其他实例可能还在使用。
     */
    public void close() {
        // 关闭 region 文件
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
     * 关闭共享数据库（在程序退出时调用）
     */
    public static void closeSharedDatabase() {
        synchronized (ClientHassiumStorage.class) {
            if (sharedDatabase != null) {
                try {
                    sharedDatabase.close();
                } catch (IOException e) {
                    Constants.LOG.warn("Failed to close shared cache database", e);
                }
                sharedDatabase = null;
            }
        }
    }

    /**
     * 清空此世界的所有缓存
     */
    public int clearAll() {
        close();
        int count = 0;
        try {
            if (Files.exists(cacheRoot)) {
                try (var stream = Files.list(cacheRoot)) {
                    for (Path file : stream.toList()) {
                        if (Files.isRegularFile(file) && file.toString().endsWith(REGION_EXTENSION)) {
                            Files.delete(file);
                            count++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            Constants.LOG.error("Failed to clear cache for server {} dimension {}", serverId, dimension, e);
        }
        return count;
    }

    /**
     * 执行缓存清理（定期调用）
     *
     * @param currentGameTime 当前游戏时间
     * @return 清理的区块数量
     */
    public int performCacheCleanup(long currentGameTime) {
        if (evictionManager == null) {
            return 0;
        }

        HassiumConfigService configService = HassiumConfigService.getInstance();
        int cleanupInterval = configService.getCleanupIntervalTicks();

        // 检查是否到达清理间隔
        if (currentGameTime - lastCleanupTick < cleanupInterval) {
            return 0;
        }

        lastCleanupTick = currentGameTime;

        // 执行清理
        return evictionManager.performCleanup(currentGameTime, configService.getConfig().clientCache());
    }

    /**
     * 获取热度统计信息
     */
    public CacheEvictionManager.HotStats getHotStats(long currentGameTime) {
        if (evictionManager == null) {
            return new CacheEvictionManager.HotStats(0, 0, 0, 0, 0);
        }
        return evictionManager.getHotStats(currentGameTime);
    }

    /**
     * 手动触发清理
     *
     * @param currentGameTime 当前游戏时间
     * @return 清理的区块数量
     */
    public int manualCleanup(long currentGameTime) {
        if (evictionManager == null) {
            return 0;
        }
        lastCleanupTick = currentGameTime; // 重置清理计时器
        return evictionManager.performCleanup(currentGameTime,
                HassiumConfigService.getInstance().getConfig().clientCache());
    }

    /**
     * 清理指定维度的缓存
     */
    public int clearDimension(String dimension) {
        if (evictionManager == null) {
            return 0;
        }
        return evictionManager.clearDimension(dimension);
    }

    /**
     * 获取数据库实例
     */
    public ClientCacheDatabase getDatabase() {
        return sharedDatabase;
    }

    /**
     * 获取服务器 ID
     */
    public String getServerId() {
        return serverId;
    }

    /**
     * 获取维度
     */
    public String getDimension() {
        return dimension;
    }

    /**
     * 获取缓存根目录
     */
    public Path getCacheRoot() {
        return cacheRoot;
    }

    /**
     * 获取或打开 region 文件（原子操作，线程安全）
     */
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

    /**
     * 获取已打开的 region 文件，如果文件存在则自动打开（原子操作，线程安全）
     */
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

    /**
     * 使用 ZSTD 字典压缩数据
     */
    private byte[] compressWithDictionary(byte[] data, int level) throws Exception {
        CompressionService service = CompressionService.getInstance();
        DictionaryRegistry registry = service.getDictionaryRegistry()
                .orElseThrow(() -> new RuntimeException("Dictionary registry not available"));

        String dictionaryId = HassiumConfigService.getInstance().getConfig().storage().zstdDictionaryId();
        byte[] dictionary = registry.findDictionary(dictionaryId)
                .orElseThrow(() -> new RuntimeException("Dictionary not found: " + dictionaryId));

        ZstdDictCompress dict = new ZstdDictCompress(dictionary, level);
        return Zstd.compress(data, dict);
    }

    /**
     * 使用 ZSTD 字典解压数据
     */
    private byte[] decompressWithDictionary(byte[] compressedData) {
        CompressionService service = CompressionService.getInstance();
        DictionaryRegistry registry = service.getDictionaryRegistry()
                .orElseThrow(() -> new RuntimeException("Dictionary registry not available"));

        String dictionaryId = HassiumConfigService.getInstance().getConfig().storage().zstdDictionaryId();
        byte[] dictionary = registry.findDictionary(dictionaryId)
                .orElseThrow(() -> new RuntimeException("Dictionary not found: " + dictionaryId));

        ZstdDictDecompress dict = new ZstdDictDecompress(dictionary);
        // 使用推荐的解压方法：decompressFastDict
        int decompressedSize = (int) Zstd.decompressedSize(compressedData);
        if (decompressedSize <= 0) {
            decompressedSize = compressedData.length * 4; // 估算值
        }
        byte[] result = new byte[decompressedSize];
        long actualSize = Zstd.decompressFastDict(result, 0, compressedData, 0, compressedData.length, dict);
        if (actualSize <= 0) {
            throw new RuntimeException("ZSTD dictionary decompression failed: invalid output");
        }
        // 如果实际大小与预估不同，截取实际大小
        if (actualSize < decompressedSize) {
            byte[] trimmed = new byte[(int) actualSize];
            System.arraycopy(result, 0, trimmed, 0, (int) actualSize);
            return trimmed;
        }
        return result;
    }
}
