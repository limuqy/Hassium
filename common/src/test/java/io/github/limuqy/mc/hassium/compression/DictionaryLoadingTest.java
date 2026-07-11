package io.github.limuqy.mc.hassium.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字典加载和字典压缩测试
 */
class DictionaryLoadingTest {

    private DictionaryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleDictionaryRegistry();
    }

    @Test
    void testLoadBuiltinDictionary() {
        int loadedCount = ResourceDictionaryLoader.loadBuiltinDictionaries(registry);

        assertTrue(loadedCount > 0, "Should load at least one builtin dictionary");
        assertTrue(registry.hasDictionary("hassium-dictionary"),
                "Should have hassium-dictionary loaded");
    }

    @Test
    void testDictionaryDescriptor() {
        ResourceDictionaryLoader.loadBuiltinDictionaries(registry);

        Optional<DictionaryDescriptor> descriptor = registry.getDescriptor("hassium-dictionary");
        assertTrue(descriptor.isPresent(), "Descriptor should be present");

        DictionaryDescriptor desc = descriptor.get();
        assertEquals("hassium-dictionary", desc.dictionaryId());
        assertEquals(CompressionAlgorithmId.HASSIUM_ZSTD_DICT, desc.algorithmId());
        assertEquals("builtin", desc.sourceProfile());
    }

    @Test
    void testDictionaryData() {
        ResourceDictionaryLoader.loadBuiltinDictionaries(registry);

        Optional<byte[]> data = registry.findDictionary("hassium-dictionary");
        assertTrue(data.isPresent(), "Dictionary data should be present");
        assertTrue(data.get().length > 0, "Dictionary data should not be empty");
    }

    @Test
    void testDictionaryVerification() throws CompressionException {
        ResourceDictionaryLoader.loadBuiltinDictionaries(registry);

        boolean verified = registry.verify("hassium-dictionary");
        assertTrue(verified, "Dictionary checksum should verify successfully");
    }

    @Test
    void testDictionaryCompression() throws CompressionException {
        ResourceDictionaryLoader.loadBuiltinDictionaries(registry);

        ZstdDictionaryCompressionCodec codec = new ZstdDictionaryCompressionCodec(registry);

        byte[] testData = "This is test NBT data for Minecraft chunk compression".repeat(10).getBytes();

        CompressionOptions options = new CompressionOptions(3, Optional.of("hassium-dictionary"), true);

        byte[] compressed = codec.compress(testData, options);
        assertNotNull(compressed, "Compressed data should not be null");
        assertTrue(compressed.length < testData.length, "Compressed data should be smaller than original");

        byte[] decompressed = codec.decompress(compressed, options);
        assertArrayEquals(testData, decompressed, "Decompressed data should match original");
    }

    @Test
    void testDictionaryCompressionWithMissingDictionary() {
        ZstdDictionaryCompressionCodec codec = new ZstdDictionaryCompressionCodec(registry);

        byte[] testData = "test data".getBytes();
        CompressionOptions options = new CompressionOptions(3, Optional.of("nonexistent-dict"), true);

        assertThrows(CompressionException.class, () -> {
            codec.compress(testData, options);
        }, "Should throw exception when dictionary is missing");
    }

    @Test
    void testHassiumCompressionInitialization() {
        HassiumCompression.reset();
        assertFalse(HassiumCompression.isInitialized());

        HassiumCompression.initialize();
        assertTrue(HassiumCompression.isInitialized());

        DictionaryRegistry reg = HassiumCompression.getDictionaryRegistry();
        assertNotNull(reg);
        assertTrue(reg.hasDictionary("hassium-dictionary"));
    }

    @Test
    void testCompressionServiceIntegration() {
        HassiumCompression.reset();
        HassiumCompression.initialize();

        CompressionService service = CompressionService.getInstance();
        assertTrue(service.getDictionaryRegistry().isPresent());

        CompressionCodec codec = service.getCodec(CompressionAlgorithmId.HASSIUM_ZSTD_DICT);
        assertNotNull(codec, "Dictionary compression codec should be registered");
        assertTrue(codec.requiresDictionary());
    }
}
