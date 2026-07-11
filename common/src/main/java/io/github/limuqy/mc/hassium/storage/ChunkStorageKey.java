package io.github.limuqy.mc.hassium.storage;

/**
 * 区块存储键，用于唯一标识一个区块
 */
public record ChunkStorageKey(
        String dimension,
        int chunkX,
        int chunkZ
) {
    /**
     * 获取 region X 坐标
     */
    public int regionX() {
        return chunkX >> 5;
    }

    /**
     * 获取 region Z 坐标
     */
    public int regionZ() {
        return chunkZ >> 5;
    }

    /**
     * 获取 region 内的本地 X 坐标 (0-31)
     */
    public int localX() {
        return chunkX & 31;
    }

    /**
     * 获取 region 内的本地 Z 坐标 (0-31)
     */
    public int localZ() {
        return chunkZ & 31;
    }

    /**
     * 获取 region 内的索引 (0-1023)
     */
    public int regionIndex() {
        return localX() + localZ() * 32;
    }

    /**
     * 创建缓存键
     */
    public String toCacheKey() {
        return String.format("%s/%d/%d", dimension, chunkX, chunkZ);
    }
}
