package io.github.limuqy.mc.hassium.compression;

import com.github.luben.zstd.Zstd;

/**
 * ZSTD 压缩编解码器
 * <p>
 * 使用 zstd-jni 库实现 ZSTD 压缩和解压。
 */
public class ZstdCompressionCodec implements CompressionCodec {

    private static final CompressionAlgorithmId ALGORITHM_ID = CompressionAlgorithmId.HASSIUM_ZSTD;

    @Override
    public CompressionAlgorithmId id() {
        return ALGORITHM_ID;
    }

    @Override
    public byte[] compress(byte[] input, CompressionOptions options) throws CompressionException {
        try {
            return Zstd.compress(input, options.level());
        } catch (Exception e) {
            throw new CompressionException.CompressionFailedException("ZSTD compression failed", e);
        }
    }

    @Override
    public byte[] decompress(byte[] input, CompressionOptions options) throws CompressionException {
        try {
            // 使用推荐的解压方法
            // Zstd.decompress(byte[], int) 会自动处理大小
            byte[] result = Zstd.decompress(input, (int) Zstd.decompressedSize(input));
            if (result == null || result.length == 0) {
                throw new CompressionException.DecompressionFailedException("ZSTD decompression failed: empty output");
            }
            return result;
        } catch (CompressionException e) {
            throw e;
        } catch (Exception e) {
            throw new CompressionException.DecompressionFailedException("ZSTD decompression failed", e);
        }
    }

    @Override
    public int[] getSupportedLevels() {
        return new int[]{1, 22};
    }

    @Override
    public int getRecommendedLevel() {
        return 3;
    }
}
