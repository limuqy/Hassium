package io.github.limuqy.mc.hassium.compression;

/**
 * 压缩编解码器接口
 */
public interface CompressionCodec {

    /**
     * 获取算法标识符
     */
    CompressionAlgorithmId id();

    /**
     * 压缩数据
     *
     * @param input   原始数据
     * @param options 压缩选项
     * @return 压缩后的数据
     * @throws CompressionException 压缩失败
     */
    byte[] compress(byte[] input, CompressionOptions options) throws CompressionException;

    /**
     * 解压数据
     *
     * @param input   压缩数据
     * @param options 压缩选项
     * @return 解压后的数据
     * @throws CompressionException 解压失败
     */
    byte[] decompress(byte[] input, CompressionOptions options) throws CompressionException;

    /**
     * 检查是否需要字典
     */
    default boolean requiresDictionary() {
        return id().requiresDictionary();
    }

    /**
     * 获取支持的压缩等级范围
     *
     * @return [min, max] 压缩等级
     */
    default int[] getSupportedLevels() {
        return new int[]{1, 22};
    }

    /**
     * 获取推荐的压缩等级
     */
    default int getRecommendedLevel() {
        return 3;
    }
}
