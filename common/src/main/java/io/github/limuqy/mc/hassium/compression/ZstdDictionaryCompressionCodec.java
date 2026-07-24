package io.github.limuqy.mc.hassium.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ZSTD 字典压缩编解码器
 * <p>
 * 使用预训练的字典进行压缩，可以提高小数据块的压缩率。
 * <p>
 * {@link ZstdDictCompress} 按 level 缓存，{@link ZstdDictDecompress} 单例缓存。
 * zstd-jni 字典句柄线程安全性未文档化，compress/decompress 以 {@code synchronized} 保护。
 */
public class ZstdDictionaryCompressionCodec implements CompressionCodec {

    private static final CompressionAlgorithmId ALGORITHM_ID = CompressionAlgorithmId.HASSIUM_ZSTD_DICT;

    private final DictionaryRegistry dictionaryRegistry;
    private final String defaultDictionaryId;

    /** 按压缩等级缓存的压缩字典句柄 */
    private final ConcurrentHashMap<Integer, ZstdDictCompress> compressCache = new ConcurrentHashMap<>();

    /** 解压字典句柄（单例缓存，volatile 保证可见性） */
    private volatile ZstdDictDecompress decompressHandle;

    public ZstdDictionaryCompressionCodec(DictionaryRegistry dictionaryRegistry) {
        this.dictionaryRegistry = dictionaryRegistry;
        this.defaultDictionaryId = io.github.limuqy.mc.hassium.Constants.DEFAULT_ZSTD_DICTIONARY_ID;
    }

    @Override
    public CompressionAlgorithmId id() {
        return ALGORITHM_ID;
    }

    @Override
    public synchronized byte[] compress(byte[] input, CompressionOptions options) throws CompressionException {
        String dictionaryId = options.dictionaryId()
                .orElseThrow(() -> new CompressionException("Dictionary ID is required for dictionary compression"));

        byte[] dictionary = dictionaryRegistry.findDictionary(dictionaryId)
                .orElseThrow(() -> new CompressionException.DictionaryLoadException(dictionaryId, "Dictionary not found"));

        int level = options.level();
        ZstdDictCompress dict = compressCache.computeIfAbsent(level, l -> {
            try {
                return new ZstdDictCompress(dictionary, l);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create ZstdDictCompress for level " + l, e);
            }
        });

        try {
            return Zstd.compress(input, dict);
        } catch (Exception e) {
            throw new CompressionException.CompressionFailedException("ZSTD dictionary compression failed", e);
        }
    }

    @Override
    public synchronized byte[] decompress(byte[] input, CompressionOptions options) throws CompressionException {
        String dictionaryId = options.dictionaryId()
                .orElseThrow(() -> new CompressionException("Dictionary ID is required for dictionary decompression"));

        byte[] dictionary = dictionaryRegistry.findDictionary(dictionaryId)
                .orElseThrow(() -> new CompressionException.DictionaryLoadException(dictionaryId, "Dictionary not found"));

        ZstdDictDecompress dict = decompressHandle;
        if (dict == null) {
            try {
                dict = new ZstdDictDecompress(dictionary);
                decompressHandle = dict;
            } catch (Exception e) {
                throw new RuntimeException("Failed to create ZstdDictDecompress", e);
            }
        }

        try {
            int decompressedSize = (int) Zstd.decompressedSize(input);
            if (decompressedSize <= 0) {
                decompressedSize = input.length * 4; // 估算值
            }
            byte[] result = new byte[decompressedSize];
            long actualSize = Zstd.decompressFastDict(result, 0, input, 0, input.length, dict);
            if (actualSize <= 0) {
                throw new CompressionException.DecompressionFailedException("ZSTD dictionary decompression failed: invalid output");
            }
            // 如果实际大小与预估不同，截取实际大小
            if (actualSize < decompressedSize) {
                byte[] trimmed = new byte[(int) actualSize];
                System.arraycopy(result, 0, trimmed, 0, (int) actualSize);
                return trimmed;
            }
            return result;
        } catch (CompressionException e) {
            throw e;
        } catch (Exception e) {
            throw new CompressionException.DecompressionFailedException("ZSTD dictionary decompression failed", e);
        }
    }

    @Override
    public boolean requiresDictionary() {
        return true;
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
