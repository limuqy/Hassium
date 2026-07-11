package io.github.limuqy.mc.hassium.compression;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZSTD 压缩测试
 */
public class ZstdCompressionTest {

    @Test
    public void testZstdCompression() throws CompressionException {
        ZstdCompressionCodec codec = new ZstdCompressionCodec();

        // 测试数据
        String testData = "Hello, World! This is a test string for ZSTD compression. ".repeat(100);
        byte[] input = testData.getBytes();

        // 压缩
        CompressionOptions options = CompressionOptions.withLevel(6);
        byte[] compressed = codec.compress(input, options);

        // 验证压缩结果
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);
        assertTrue(compressed.length < input.length, "Compressed data should be smaller");

        // 解压
        byte[] decompressed = codec.decompress(compressed, options);

        // 验证解压结果
        assertNotNull(decompressed);
        assertArrayEquals(input, decompressed, "Decompressed data should match original");

        System.out.printf("Original: %d bytes, Compressed: %d bytes, Ratio: %.2f%%%n",
                input.length, compressed.length, (double) compressed.length / input.length * 100);
    }

    @Test
    public void testZstdCompressionLevels() throws CompressionException {
        ZstdCompressionCodec codec = new ZstdCompressionCodec();

        // 测试数据
        byte[] input = new byte[1000000];
        Random random = new Random();
        random.nextBytes(input);

        // 测试不同压缩等级
        for (int level : new int[]{1, 3, 9, 22}) {
            CompressionOptions options = CompressionOptions.withLevel(level);
            byte[] compressed = codec.compress(input, options);
            byte[] decompressed = codec.decompress(compressed, options);

            assertArrayEquals(input, decompressed, "Decompressed data should match original for level " + level);

            System.out.printf("Level %2d: %d -> %d bytes (%.2f%%)%n",
                    level, input.length, compressed.length, (double) compressed.length / input.length * 100);
        }
    }

    @Test
    public void testVanillaZlibCompression() throws CompressionException {
        VanillaZlibCodec codec = new VanillaZlibCodec();

        // 测试数据
        String testData = "Hello, World! This is a test string for Zlib compression. ".repeat(100);
        byte[] input = testData.getBytes();

        // 压缩
        CompressionOptions options = CompressionOptions.withLevel(6);
        byte[] compressed = codec.compress(input, options);

        // 验证压缩结果
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);
        assertTrue(compressed.length < input.length, "Compressed data should be smaller");

        // 解压
        byte[] decompressed = codec.decompress(compressed, options);

        // 验证解压结果
        assertNotNull(decompressed);
        assertArrayEquals(input, decompressed, "Decompressed data should match original");

        System.out.printf("Zlib - Original: %d bytes, Compressed: %d bytes, Ratio: %.2f%%%n",
                input.length, compressed.length, (double) compressed.length / input.length * 100);
    }
}
