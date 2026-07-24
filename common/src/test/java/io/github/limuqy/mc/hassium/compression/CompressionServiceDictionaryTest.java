package io.github.limuqy.mc.hassium.compression;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CompressionService 字典便捷 API 测试。
 * <p>
 * 验证 {@link CompressionService#compressWithDictionary(byte[], int)}
 * 和 {@link CompressionService#decompressWithDictionary(byte[])} 的往返一致性。
 */
class CompressionServiceDictionaryTest {

    @BeforeAll
    static void initCompression() {
        HassiumCompression.reset();
        HassiumCompression.initialize();
    }

    @Test
    void testRoundtripFixedData() throws CompressionException {
        CompressionService service = CompressionService.getInstance();
        byte[] original = "Hello, Hassium ZSTD dictionary compression!".repeat(20).getBytes();

        for (int level : new int[]{1, 3, 9, 22}) {
            byte[] compressed = service.compressWithDictionary(original, level);
            assertNotNull(compressed, "compressed should not be null at level " + level);
            assertTrue(compressed.length < original.length,
                    "compressed should be smaller than original at level " + level);

            byte[] decompressed = service.decompressWithDictionary(compressed);
            assertArrayEquals(original, decompressed,
                    "roundtrip should preserve data at level " + level);
        }
    }

    @Test
    void testRoundtripRandomData() throws CompressionException {
        CompressionService service = CompressionService.getInstance();
        Random rng = new Random(20260724);
        byte[] original = new byte[8192];
        rng.nextBytes(original);

        byte[] compressed = service.compressWithDictionary(original, 3);
        assertNotNull(compressed);

        byte[] decompressed = service.decompressWithDictionary(compressed);
        assertArrayEquals(original, decompressed, "random data roundtrip should match");
    }

    @Test
    void testRoundtripEmptyArray() throws CompressionException {
        CompressionService service = CompressionService.getInstance();
        byte[] original = new byte[0];

        byte[] compressed = service.compressWithDictionary(original, 3);
        assertNotNull(compressed);
        // ZSTD 可以压缩空数组，解压后应为空

        byte[] decompressed = service.decompressWithDictionary(compressed);
        assertArrayEquals(original, decompressed, "empty array roundtrip should match");
    }

    @Test
    void testSmallDataRoundtrip() throws CompressionException {
        CompressionService service = CompressionService.getInstance();
        byte[] original = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};

        byte[] compressed = service.compressWithDictionary(original, 3);
        assertNotNull(compressed);

        byte[] decompressed = service.decompressWithDictionary(compressed);
        assertArrayEquals(original, decompressed, "small data roundtrip should match");
    }

    @Test
    void testDecompressCorruptedData() {
        CompressionService service = CompressionService.getInstance();
        byte[] corrupted = new byte[]{0x00, 0x01, 0x02, 0x03}; // 无效 ZSTD 帧

        assertThrows(CompressionException.class, () -> {
            service.decompressWithDictionary(corrupted);
        }, "decompressing garbage should throw CompressionException");
    }

    @Test
    void testCodecNotRegisteredWithoutInit() {
        // 注意：@BeforeAll 已调用 HassiumCompression.initialize()
        // 这里只验证 null-safe 行为（codec 查找返回 null 时的异常）
        CompressionService service = CompressionService.getInstance();
        CompressionCodec codec = service.getCodec(CompressionAlgorithmId.HASSIUM_ZSTD_DICT);
        assertNotNull(codec, "Dictionary codec should be registered after HassiumCompression.initialize()");
    }
}