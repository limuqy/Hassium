package io.github.limuqy.mc.hassium.storage;

/**
 * 区块 payload 数据
 */
public record ChunkPayload(
        byte[] data,
        CompressionType compressionType,
        ChunkStorageMetadata metadata
) {
    /**
     * 压缩类型枚举
     */
    public enum CompressionType {
        /**
         * 原版无压缩 (scheme 0)
         */
        NONE(0),

        /**
         * 原版 GZip (scheme 1)
         */
        GZIP(1),

        /**
         * 原版 Zlib (scheme 2)
         */
        ZLIB(2),

        /**
         * 原版 LZ4 (scheme 3, 1.20.5+)
         */
        LZ4(3),

        /**
         * Hassium ZSTD 字典压缩 (scheme 126)
         */
        HASSIUM_ZSTD(126);

        private final int schemeId;

        CompressionType(int schemeId) {
            this.schemeId = schemeId;
        }

        /**
         * 获取压缩方案 ID
         */
        public int getSchemeId() {
            return schemeId;
        }

        /**
         * 从方案 ID 解析
         */
        public static CompressionType fromSchemeId(int schemeId) {
            for (CompressionType type : values()) {
                if (type.schemeId == schemeId) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown compression scheme: " + schemeId);
        }

        /**
         * 检查是否为 Hassium 压缩
         */
        public boolean isHassium() {
            return this == HASSIUM_ZSTD;
        }
    }

    /**
     * 获取数据大小（字节）
     */
    public int size() {
        return data.length;
    }

    /**
     * 检查是否为 Hassium 压缩
     */
    public boolean isHassiumCompressed() {
        return compressionType.isHassium();
    }
}
