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
    /** review-fix: T5-88 解压输出上限（防巨型 content size 声明 / zip bomb） */
    private static final int MAX_DECOMPRESSED_SIZE = 64 * 1024 * 1024;

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
            long contentSize = Zstd.getFrameContentSize(input);
            // review-fix: T5-88 超限拒绝——巨大声明（及 -1 强转负值）在分配前拦截
            if (contentSize > MAX_DECOMPRESSED_SIZE) {
                throw new CompressionException.DecompressionFailedException(
                        "ZSTD decompression failed: declared content size " + contentSize
                                + " exceeds limit " + MAX_DECOMPRESSED_SIZE);
            }
            if (contentSize == 0) {
                // review-fix: T5-88 content size 为 0 的合法空内容帧按空数组处理（此前误判为失败）
                return new byte[0];
            }
            // contentSize 已知时按声明精确分配；未知（-1）时以上限兜底（native 侧仍校验实际大小）
            byte[] result = Zstd.decompress(input, contentSize < 0 ? MAX_DECOMPRESSED_SIZE : (int) contentSize);
            if (result == null) {
                throw new CompressionException.DecompressionFailedException("ZSTD decompression failed: null output");
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
