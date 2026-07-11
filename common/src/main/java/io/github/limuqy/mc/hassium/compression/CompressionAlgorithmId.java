package io.github.limuqy.mc.hassium.compression;

/**
 * 压缩算法标识符
 * <p>
 * 使用命名空间格式，例如 "hassium:zstd"、"hassium:zstd_dict"
 */
public record CompressionAlgorithmId(String namespace, String path) {

    /**
     * Hassium ZSTD 无字典压缩
     */
    public static final CompressionAlgorithmId HASSIUM_ZSTD = new CompressionAlgorithmId("hassium", "zstd");

    /**
     * Hassium ZSTD 字典压缩
     */
    public static final CompressionAlgorithmId HASSIUM_ZSTD_DICT = new CompressionAlgorithmId("hassium", "zstd_dict");

    /**
     * 原版 Zlib
     */
    public static final CompressionAlgorithmId VANILLA_ZLIB = new CompressionAlgorithmId("minecraft", "zlib");

    /**
     * 原版 GZip
     */
    public static final CompressionAlgorithmId VANILLA_GZIP = new CompressionAlgorithmId("minecraft", "gzip");

    /**
     * 原版 LZ4 (1.20.5+)
     */
    public static final CompressionAlgorithmId VANILLA_LZ4 = new CompressionAlgorithmId("minecraft", "lz4");

    /**
     * 从字符串解析算法 ID
     *
     * @param id 命名空间格式的 ID，例如 "hassium:zstd"
     * @return 算法 ID 实例
     */
    public static CompressionAlgorithmId parse(String id) {
        String[] parts = id.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid algorithm ID format: " + id);
        }
        return new CompressionAlgorithmId(parts[0], parts[1]);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    /**
     * 检查是否为 Hassium 自定义算法
     */
    public boolean isHassiumAlgorithm() {
        return "hassium".equals(namespace);
    }

    /**
     * 检查是否需要字典
     */
    public boolean requiresDictionary() {
        return this.equals(HASSIUM_ZSTD_DICT);
    }
}
