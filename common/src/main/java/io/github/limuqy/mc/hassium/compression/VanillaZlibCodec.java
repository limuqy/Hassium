package io.github.limuqy.mc.hassium.compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 原版 Zlib 压缩编解码器
 * <p>
 * 用于对比、回滚、导出原版 payload。
 */
public class VanillaZlibCodec implements CompressionCodec {

    private static final CompressionAlgorithmId ALGORITHM_ID = CompressionAlgorithmId.VANILLA_ZLIB;

    @Override
    public CompressionAlgorithmId id() {
        return ALGORITHM_ID;
    }

    @Override
    public byte[] compress(byte[] input, CompressionOptions options) throws CompressionException {
        Deflater deflater = new Deflater(options.level());
        try {
            deflater.setInput(input);
            deflater.finish();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                outputStream.write(buffer, 0, count);
            }

            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new CompressionException.CompressionFailedException("Zlib compression failed", e);
        } finally {
            deflater.end();
        }
    }

    @Override
    public byte[] decompress(byte[] input, CompressionOptions options) throws CompressionException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput()) {
                        throw new CompressionException.DecompressionFailedException("Zlib decompression failed: needs more input");
                    }
                    break;
                }
                outputStream.write(buffer, 0, count);
            }

            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new CompressionException.DecompressionFailedException("Zlib decompression failed", e);
        } finally {
            inflater.end();
        }
    }

    @Override
    public int[] getSupportedLevels() {
        return new int[]{1, 9};
    }

    @Override
    public int getRecommendedLevel() {
        return 6;
    }
}
