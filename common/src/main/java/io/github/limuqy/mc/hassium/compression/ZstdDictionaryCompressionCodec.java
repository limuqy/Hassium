package io.github.limuqy.mc.hassium.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdException;

import java.util.Arrays;
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

    /** review-fix: T5-89 解压输出上限（防巨型 content size 声明 / zip bomb） */
    private static final int MAX_DECOMPRESSED_SIZE = 64 * 1024 * 1024;

    /** 按压缩等级缓存的压缩字典句柄（键带内容版本，见 {@link #contentVersion}） */
    private final ConcurrentHashMap<Integer, CachedDictHandle<ZstdDictCompress>> compressCache = new ConcurrentHashMap<>();

    /** 解压字典句柄（单例缓存，volatile 保证可见性；字典内容变更时重建） */
    private volatile CachedDictHandle<ZstdDictDecompress> decompressHandle;

    /**
     * review-fix: T5-94 句柄缓存按字典内容失效——以描述符校验和作为内容版本：
     * 同名不同内容字典热替换（unregister + register）后版本变化，句柄自动重建。
     */
    private long contentVersion(String dictionaryId) {
        return dictionaryRegistry.getDescriptor(dictionaryId)
                .map(DictionaryDescriptor::dictionaryChecksum)
                .orElse(0L);
    }

    private static final class CachedDictHandle<T> {
        final long contentVersion;
        final T handle;

        CachedDictHandle(long contentVersion, T handle) {
            this.contentVersion = contentVersion;
            this.handle = handle;
        }
    }

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
        // review-fix: T5-91 句柄创建失败包装 DictionaryLoadException（不逃逸裸 RuntimeException）
        // review-fix: T5-94 缓存携带内容版本——同名不同内容字典热替换后句柄自动重建
        long version = contentVersion(dictionaryId);
        CachedDictHandle<ZstdDictCompress> cached = compressCache.get(level);
        ZstdDictCompress dict = (cached != null && cached.contentVersion == version) ? cached.handle : null;
        if (dict == null) {
            try {
                dict = new ZstdDictCompress(dictionary, level);
            } catch (Exception e) {
                throw new CompressionException.DictionaryLoadException(dictionaryId,
                        "Failed to create ZstdDictCompress for level " + level, e);
            }
            compressCache.put(level, new CachedDictHandle<>(version, dict));
        }

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

        // review-fix: T5-91 句柄创建失败包装 DictionaryLoadException（不逃逸裸 RuntimeException）
        long version = contentVersion(dictionaryId);
        CachedDictHandle<ZstdDictDecompress> cached = decompressHandle;
        ZstdDictDecompress dict = (cached != null && cached.contentVersion == version) ? cached.handle : null;
        if (dict == null) {
            try {
                dict = new ZstdDictDecompress(dictionary);
            } catch (Exception e) {
                throw new CompressionException.DictionaryLoadException(dictionaryId,
                        "Failed to create ZstdDictDecompress", e);
            }
            // review-fix: T5-94 解压句柄缓存同样按内容版本失效
            decompressHandle = new CachedDictHandle<>(version, dict);
        }

        try {
            long contentSize = Zstd.getFrameContentSize(input);
            // review-fix: T5-89 超限拒绝——巨大声明在分配前拦截
            if (contentSize > MAX_DECOMPRESSED_SIZE) {
                throw new CompressionException.DecompressionFailedException(
                        "ZSTD dictionary decompression failed: declared content size " + contentSize
                                + " exceeds limit " + MAX_DECOMPRESSED_SIZE);
            }
            if (contentSize == 0) {
                // 合法空内容帧（content size 0）
                return new byte[0];
            }
            if (contentSize > 0) {
                // 已知大小：便捷 API 精确分配（native 校验实际输出，超限/损坏抛 ZstdException）
                return Zstd.decompress(input, dict, (int) contentSize);
            }
            // review-fix: T5-89 content size 未知：ZstdDecompressCtx 动态扩容解压
            // （上限 MAX_DECOMPRESSED_SIZE；高压缩比输入不再因估算不足误报失败）
            try (ZstdDecompressCtx ctx = new ZstdDecompressCtx()) {
                ctx.loadDict(dict);
                int capacity = Math.max(input.length * 4, 1024);
                while (capacity <= MAX_DECOMPRESSED_SIZE) {
                    byte[] result = new byte[capacity];
                    try {
                        int actual = ctx.decompressByteArray(result, 0, capacity, input, 0, input.length);
                        return actual == capacity ? result : Arrays.copyOf(result, actual);
                    } catch (ZstdException e) {
                        if (e.getErrorCode() != Zstd.errDstSizeTooSmall()) {
                            throw e;
                        }
                        if (capacity > MAX_DECOMPRESSED_SIZE / 2) {
                            break; // 再翻倍将超上限
                        }
                        capacity *= 2;
                    }
                }
                throw new CompressionException.DecompressionFailedException(
                        "ZSTD dictionary decompression failed: output exceeds limit " + MAX_DECOMPRESSED_SIZE);
            }
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
