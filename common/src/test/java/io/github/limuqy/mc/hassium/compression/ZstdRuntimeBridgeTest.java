package io.github.limuqy.mc.hassium.compression;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZstdRuntimeBridge 嵌入隔离路径回归。
 * <p>
 * common 的 test classpath 上没有外部 zstd-jni（见 common/build.gradle，零编译/测试期依赖），
 * 外部回退默认关闭（-Dhassium.zstdExternalFallback）——本类任何用例能跑通即证明
 * 「嵌入资源提取 → 隔离 URLClassLoader → 反射绑定」全链路可用；隔离断链会直接
 * IllegalStateException 而非静默降级。
 */
class ZstdRuntimeBridgeTest {

    @Test
    void bridgeLoadsFromEmbeddedIsolatedLoader() {
        assertTrue(ZstdRuntimeBridge.isIsolated(),
                "bridge must bind the embedded isolated loader; external fallback is off in tests");
    }

    @Test
    void staticRoundTrip() {
        byte[] raw = randomPayload(64 * 1024, 1);
        byte[] compressed = ZstdRuntimeBridge.compress(raw, 3);
        assertEquals(raw.length, ZstdRuntimeBridge.getFrameContentSize(compressed));
        assertArrayEquals(raw, ZstdRuntimeBridge.decompress(compressed, raw.length));
    }

    @Test
    void compressUsingDictRoundTrip() {
        byte[] raw = structuredPayload(32 * 1024, 2);
        byte[] dict = structuredPayload(8 * 1024, 3);
        byte[] compressed = ZstdRuntimeBridge.compressUsingDict(raw, dict, 3);
        // raw-content 字典帧必须带同一字典解（生产聚合包路径同款 ctx.loadDict(byte[])）
        try (ZstdRuntimeBridge.DecompressCtx ctx = ZstdRuntimeBridge.newDecompressCtx()) {
            ctx.loadDict(dict);
            assertArrayEquals(raw, ctx.decompress(compressed, raw.length));
        }
    }

    @Test
    void dictHandleRoundTripWithClose() {
        byte[] raw = structuredPayload(32 * 1024, 4);
        byte[] dict = structuredPayload(4 * 1024, 5);
        try (ZstdRuntimeBridge.CompressDict compress = ZstdRuntimeBridge.newCompressDict(dict, 3);
             ZstdRuntimeBridge.DecompressDict decompress = ZstdRuntimeBridge.newDecompressDict(dict)) {
            byte[] compressed = ZstdRuntimeBridge.compress(raw, compress);
            assertArrayEquals(raw, ZstdRuntimeBridge.decompress(compressed, decompress, raw.length));
        }
    }

    @Test
    void magiclessContextRoundTrip() {
        byte[] raw = randomPayload(16 * 1024, 6);
        byte[] compressed;
        try (ZstdRuntimeBridge.CompressCtx ctx = ZstdRuntimeBridge.newCompressCtx()) {
            ctx.setLevel(3);
            ctx.setMagicless(true);
            compressed = ctx.compress(raw);
        }
        try (ZstdRuntimeBridge.DecompressCtx ctx = ZstdRuntimeBridge.newDecompressCtx()) {
            ctx.setMagicless(true);
            assertArrayEquals(raw, ctx.decompress(compressed, raw.length));
        }
    }

    @Test
    void contextAcceptsRawByteDictionary() {
        byte[] raw = structuredPayload(16 * 1024, 8);
        byte[] dict = structuredPayload(4 * 1024, 9);
        byte[] compressed;
        try (ZstdRuntimeBridge.CompressCtx ctx = ZstdRuntimeBridge.newCompressCtx()) {
            ctx.setLevel(3);
            ctx.loadDict(dict);
            compressed = ctx.compress(raw);
        }
        try (ZstdRuntimeBridge.DecompressCtx ctx = ZstdRuntimeBridge.newDecompressCtx()) {
            ctx.loadDict(dict);
            assertArrayEquals(raw, ctx.decompress(compressed, raw.length));
        }
    }

    @Test
    void dstSizeTooSmallCarriesNativeErrorCode() {
        byte[] raw = randomPayload(4096, 7);
        byte[] compressed = ZstdRuntimeBridge.compress(raw, 3);
        try (ZstdRuntimeBridge.DecompressCtx ctx = ZstdRuntimeBridge.newDecompressCtx()) {
            byte[] tiny = new byte[16];
            ZstdRuntimeBridge.ZstdErrorException failure = assertThrows(
                    ZstdRuntimeBridge.ZstdErrorException.class,
                    () -> ctx.decompressByteArray(tiny, 0, tiny.length, compressed, 0, compressed.length));
            assertEquals(ZstdRuntimeBridge.errDstSizeTooSmall(), failure.errorCode());
        }
    }

    @Test
    void dictTrainerProducesUsableDictionary() {
        byte[][] samples = new byte[64][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = structuredPayload(1024, 100 + i);
        }
        ZstdRuntimeBridge.DictTrainer trainer = ZstdRuntimeBridge.newDictTrainer(samples.length * 1024, 4 * 1024);
        for (byte[] sample : samples) {
            trainer.addSample(sample);
        }
        byte[] dictionary = trainer.trainSamples();
        assertNotNull(dictionary);
        assertTrue(dictionary.length > 0 && dictionary.length <= 4 * 1024);
    }

    private static byte[] randomPayload(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    // 字典训练对结构化数据稳定；纯随机样本可能被 ZDICT 拒绝
    private static byte[] structuredPayload(int size, long seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ('a' + ((i * 31 + seed * 7) % 26));
        }
        return data;
    }
}
