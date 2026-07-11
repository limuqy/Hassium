package io.github.limuqy.mc.hassium.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;

/**
 * ZSTD 字典压缩编解码器
 * <p>
 * 使用预训练的字典进行压缩，可以提高小数据块的压缩率。
 */
public class ZstdDictionaryCompressionCodec implements CompressionCodec {

    private static final CompressionAlgorithmId ALGORITHM_ID = CompressionAlgorithmId.HASSIUM_ZSTD_DICT;

    private final DictionaryRegistry dictionaryRegistry;

    public ZstdDictionaryCompressionCodec(DictionaryRegistry dictionaryRegistry) {
        this.dictionaryRegistry = dictionaryRegistry;
    }

    @Override
    public CompressionAlgorithmId id() {
        return ALGORITHM_ID;
    }

    @Override
    public byte[] compress(byte[] input, CompressionOptions options) throws CompressionException {
        String dictionaryId = options.dictionaryId()
                .orElseThrow(() -> new CompressionException("Dictionary ID is required for dictionary compression"));

        byte[] dictionary = dictionaryRegistry.findDictionary(dictionaryId)
                .orElseThrow(() -> new CompressionException.DictionaryLoadException(dictionaryId, "Dictionary not found"));

        try {
            ZstdDictCompress dict = new ZstdDictCompress(dictionary, options.level());
            return Zstd.compress(input, dict);
        } catch (Exception e) {
            throw new CompressionException.CompressionFailedException("ZSTD dictionary compression failed", e);
        }
    }

    @Override
    public byte[] decompress(byte[] input, CompressionOptions options) throws CompressionException {
        String dictionaryId = options.dictionaryId()
                .orElseThrow(() -> new CompressionException("Dictionary ID is required for dictionary decompression"));

        byte[] dictionary = dictionaryRegistry.findDictionary(dictionaryId)
                .orElseThrow(() -> new CompressionException.DictionaryLoadException(dictionaryId, "Dictionary not found"));

        try {
            ZstdDictDecompress dict = new ZstdDictDecompress(dictionary);
            // 使用推荐的解压方法：decompressFastDict
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
        return 9;
    }
}
