package io.github.limuqy.mc.hassium.storage;

/**
 * 区块存储元数据
 */
public record ChunkStorageMetadata(
        int dataVersion,
        long lastModifiedGameTime,
        long lastSavedUnixTime
) {
    /**
     * 创建默认元数据
     */
    public static ChunkStorageMetadata of(int dataVersion) {
        return new ChunkStorageMetadata(
                dataVersion,
                0,
                System.currentTimeMillis() / 1000
        );
    }

    /**
     * 创建带游戏时间的元数据
     */
    public static ChunkStorageMetadata of(int dataVersion, long lastModifiedGameTime) {
        return new ChunkStorageMetadata(
                dataVersion,
                lastModifiedGameTime,
                System.currentTimeMillis() / 1000
        );
    }

    /**
     * 获取 unix timestamp（秒）
     */
    public int getUnixTimestamp() {
        return (int) (lastSavedUnixTime & 0xFFFFFFFFL);
    }
}
