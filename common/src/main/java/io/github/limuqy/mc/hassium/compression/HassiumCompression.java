package io.github.limuqy.mc.hassium.compression;

import io.github.limuqy.mc.hassium.Constants;

/**
 * Hassium 压缩系统初始化器
 * <p>
 * 负责初始化字典注册表和压缩编解码器。
 */
public class HassiumCompression {

    private static DictionaryRegistry dictionaryRegistry;
    private static boolean initialized = false;

    /**
     * 初始化压缩系统
     */
    public static void initialize() {
        if (initialized) {
            Constants.LOG.warn("HassiumCompression already initialized");
            return;
        }

        Constants.LOG.info("Initializing Hassium compression system");

        try {
            // 创建字典注册表
            dictionaryRegistry = new SimpleDictionaryRegistry();

            // 加载内置字典
            int loadedCount = ResourceDictionaryLoader.loadBuiltinDictionaries(dictionaryRegistry);
            Constants.LOG.info("Loaded {} builtin dictionaries", loadedCount);

            // 获取压缩服务并注册字典压缩编解码器
            CompressionService compressionService = CompressionService.getInstance();
            compressionService.registerCodec(new ZstdDictionaryCompressionCodec(dictionaryRegistry));
            compressionService.setDictionaryRegistry(dictionaryRegistry);

            initialized = true;
            Constants.LOG.info("Hassium compression system initialized successfully");

        } catch (Exception e) {
            Constants.LOG.error("Failed to initialize Hassium compression system", e);
            throw new RuntimeException("Compression system initialization failed", e);
        }
    }

    /**
     * 获取字典注册表
     */
    public static DictionaryRegistry getDictionaryRegistry() {
        if (!initialized) {
            throw new IllegalStateException("HassiumCompression not initialized");
        }
        return dictionaryRegistry;
    }

    /**
     * 检查是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 重置压缩系统（主要用于测试）
     */
    public static void reset() {
        dictionaryRegistry = null;
        initialized = false;
        Constants.LOG.info("Hassium compression system reset");
    }
}
