package io.github.limuqy.mc.hassium.compression;

import io.github.limuqy.mc.hassium.Constants;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 压缩服务
 * <p>
 * 管理和提供压缩编解码器。
 */
public class CompressionService {

    private static final CompressionService INSTANCE = new CompressionService();

    // review-fix: T5-92 并发安全——registerCodec（初始化线程）与 getCodec（使用线程）可并发
    private final Map<String, CompressionCodec> codecs = new ConcurrentHashMap<>();
    private DictionaryRegistry dictionaryRegistry;

    private CompressionService() {
        // 注册默认编解码器
        registerCodec(new ZstdCompressionCodec());
        registerCodec(new VanillaZlibCodec());
    }

    /**
     * 获取单例实例
     */
    public static CompressionService getInstance() {
        return INSTANCE;
    }

    /**
     * 注册压缩编解码器
     */
    public void registerCodec(CompressionCodec codec) {
        codecs.put(codec.id().toString(), codec);
    }

    /**
     * 获取压缩编解码器
     *
     * @param algorithmId 算法 ID
     * @return 编解码器，如果未找到则返回 null
     */
    public CompressionCodec getCodec(String algorithmId) {
        return codecs.get(algorithmId);
    }

    /**
     * 获取压缩编解码器
     *
     * @param algorithmId 算法 ID
     * @return 编解码器，如果未找到则返回 null
     */
    public CompressionCodec getCodec(CompressionAlgorithmId algorithmId) {
        return codecs.get(algorithmId.toString());
    }

    /**
     * 压缩数据（无字典）
     *
     * @param data        原始数据
     * @param algorithmId 算法 ID
     * @param level       压缩等级
     * @return 压缩后的数据
     * @throws CompressionException 压缩失败
     */
    public byte[] compress(byte[] data, String algorithmId, int level) throws CompressionException {
        CompressionCodec codec = getCodec(algorithmId);
        if (codec == null) {
            throw new CompressionException.CompressionFailedException("Unknown compression algorithm: " + algorithmId);
        }
        CompressionOptions options = new CompressionOptions(level, Optional.empty(), true);
        return codec.compress(data, options);
    }

    /**
     * 解压数据（无字典）
     *
     * @param data        压缩数据
     * @param algorithmId 算法 ID
     * @return 解压后的数据
     * @throws CompressionException 解压失败
     */
    public byte[] decompress(byte[] data, String algorithmId) throws CompressionException {
        CompressionCodec codec = getCodec(algorithmId);
        if (codec == null) {
            throw new CompressionException.DecompressionFailedException("Unknown compression algorithm: " + algorithmId);
        }
        CompressionOptions options = CompressionOptions.DEFAULT;
        return codec.decompress(data, options);
    }

    /**
     * 使用内置 ZSTD 字典压缩数据
     * <p>
     * 固定使用 {@link Constants#DEFAULT_ZSTD_DICTIONARY_ID} 字典，
     * 经已注册的 {@link ZstdDictionaryCompressionCodec} 完成压缩。
     *
     * @param data  原始数据
     * @param level 压缩等级 (1–22)
     * @return 压缩后的数据
     * @throws CompressionException 压缩失败（codec 未注册 / 字典缺失 / zstd 内部错误）
     */
    public byte[] compressWithDictionary(byte[] data, int level) throws CompressionException {
        CompressionCodec codec = getCodec(CompressionAlgorithmId.HASSIUM_ZSTD_DICT);
        if (codec == null) {
            throw new CompressionException.CompressionFailedException(
                    "Dictionary compression codec not registered: " + CompressionAlgorithmId.HASSIUM_ZSTD_DICT);
        }
        CompressionOptions options = CompressionOptions.withDictionary(Constants.DEFAULT_ZSTD_DICTIONARY_ID, level);
        return codec.compress(data, options);
    }

    /**
     * 使用内置 ZSTD 字典解压数据
     * <p>
     * 固定使用 {@link Constants#DEFAULT_ZSTD_DICTIONARY_ID} 字典，
     * 经已注册的 {@link ZstdDictionaryCompressionCodec} 完成解压。
     *
     * @param compressedData ZSTD 字典压缩的数据
     * @return 解压后的原始数据
     * @throws CompressionException 解压失败（codec 未注册 / 字典缺失 / zstd 内部错误）
     */
    public byte[] decompressWithDictionary(byte[] compressedData) throws CompressionException {
        CompressionCodec codec = getCodec(CompressionAlgorithmId.HASSIUM_ZSTD_DICT);
        if (codec == null) {
            throw new CompressionException.DecompressionFailedException(
                    "Dictionary compression codec not registered: " + CompressionAlgorithmId.HASSIUM_ZSTD_DICT);
        }
        // level 对解压无意义，传推荐等级即可
        CompressionOptions options = CompressionOptions.withDictionary(
                Constants.DEFAULT_ZSTD_DICTIONARY_ID, codec.getRecommendedLevel());
        return codec.decompress(compressedData, options);
    }

    /**
     * 检查是否支持指定算法
     */
    public boolean isSupported(String algorithmId) {
        return codecs.containsKey(algorithmId);
    }

    /**
     * 设置字典注册表
     */
    public void setDictionaryRegistry(DictionaryRegistry dictionaryRegistry) {
        this.dictionaryRegistry = dictionaryRegistry;
    }

    /**
     * 获取字典注册表
     */
    public Optional<DictionaryRegistry> getDictionaryRegistry() {
        return Optional.ofNullable(dictionaryRegistry);
    }
}
