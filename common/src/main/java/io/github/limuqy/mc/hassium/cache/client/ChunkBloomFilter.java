package io.github.limuqy.mc.hassium.cache.client;

import java.util.BitSet;

/**
 * 区块 Bloom Filter
 * <p>
 * 用于快速判断区块是否可能存在于缓存中，减少无效的 .mca 文件读取。
 * 当 Bloom Filter 表示区块不存在时，可以确定区块不在缓存中（无假阴性）。
 * 当 Bloom Filter 表示区块存在时，区块可能在缓存中（有假阳性）。
 * <p>
 * 线程安全：所有操作都是原子的。
 */
public class ChunkBloomFilter {

    /**
     * 位数组
     */
    private final BitSet bitSet;

    /**
     * 位数组大小（位）
     */
    private final int size;

    /**
     * 哈希函数数量
     */
    private final int hashCount;

    /**
     * 已插入元素数量
     */
    private int insertCount;

    /**
     * 创建 Bloom Filter
     *
     * @param expectedInsertions 预期插入元素数量
     * @param fpp               期望的假阳性率（false positive probability）
     */
    public ChunkBloomFilter(int expectedInsertions, double fpp) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("Expected insertions must be positive");
        }
        if (fpp <= 0 || fpp >= 1) {
            throw new IllegalArgumentException("False positive probability must be between 0 and 1");
        }

        // 计算最优位数组大小: m = -n * ln(p) / (ln(2)^2)
        this.size = optimalSize(expectedInsertions, fpp);
        // 计算最优哈希函数数量: k = (m/n) * ln(2)
        this.hashCount = optimalHashCount(size, expectedInsertions);
        this.bitSet = new BitSet(size);
        this.insertCount = 0;
    }

    /**
     * 创建默认配置的 Bloom Filter（10000 元素，1% 误判率）
     */
    public static ChunkBloomFilter createDefault() {
        return new ChunkBloomFilter(10000, 0.01);
    }

    /**
     * 从配置创建 Bloom Filter
     */
    public static ChunkBloomFilter fromConfig() {
        io.github.limuqy.mc.hassium.config.HassiumConfigService configService =
                io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance();
        int expectedInsertions = configService.getBloomFilterExpectedInsertions();
        double fpp = configService.getBloomFilterFpp();
        return new ChunkBloomFilter(expectedInsertions, fpp);
    }

    /**
     * 向 Bloom Filter 添加元素
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @param dimension 维度标识
     */
    public synchronized void put(int chunkX, int chunkZ, String dimension) {
        long hash = hash(chunkX, chunkZ, dimension);
        for (int i = 0; i < hashCount; i++) {
            int index = getIndex(hash, i);
            bitSet.set(index);
        }
        insertCount++;
    }

    /**
     * 检查元素是否可能存在于 Bloom Filter 中
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @param dimension 维度标识
     * @return true 表示可能存在（可能有假阳性），false 表示一定不存在
     */
    public synchronized boolean mightContain(int chunkX, int chunkZ, String dimension) {
        long hash = hash(chunkX, chunkZ, dimension);
        for (int i = 0; i < hashCount; i++) {
            int index = getIndex(hash, i);
            if (!bitSet.get(index)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 清空 Bloom Filter
     */
    public synchronized void clear() {
        bitSet.clear();
        insertCount = 0;
    }

    /**
     * 获取已插入元素数量
     */
    public synchronized int getInsertCount() {
        return insertCount;
    }

    /**
     * 获取位数组大小（位）
     */
    public int getSize() {
        return size;
    }

    /**
     * 获取哈希函数数量
     */
    public int getHashCount() {
        return hashCount;
    }

    /**
     * 计算哈希值
     */
    private long hash(int chunkX, int chunkZ, String dimension) {
        // 使用 xxHash64 风格的混合哈希
        long h = 0x9747b28cL; // 种子
        h ^= Integer.toUnsignedLong(chunkX) * 0x517cc1b727220a95L;
        h = Long.rotateLeft(h, 31);
        h ^= Integer.toUnsignedLong(chunkZ) * 0x6c62272e07bb0142L;
        h = Long.rotateLeft(h, 27);
        h ^= dimension.hashCode() * 0x165667b19e3779f9L;
        h = Long.rotateLeft(h, 31);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }

    /**
     * 根据哈希值和第 i 个哈希函数计算索引
     * <p>
     * 使用 double hashing 技术：index = (h1 + i * h2) % size
     */
    private int getIndex(long hash, int i) {
        long h1 = hash;
        long h2 = hash >>> 32;
        long index = (h1 + (long) i * h2) % size;
        if (index < 0) {
            index += size;
        }
        return (int) index;
    }

    /**
     * 计算最优位数组大小
     * <p>
     * m = -n * ln(p) / (ln(2)^2)
     */
    private static int optimalSize(int expectedInsertions, double fpp) {
        return (int) (-expectedInsertions * Math.log(fpp) / (Math.log(2) * Math.log(2)));
    }

    /**
     * 计算最优哈希函数数量
     * <p>
     * k = (m/n) * ln(2)
     */
    private static int optimalHashCount(int size, int expectedInsertions) {
        return Math.max(1, (int) Math.round((double) size / expectedInsertions * Math.log(2)));
    }
}
