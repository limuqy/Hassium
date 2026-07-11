package io.github.limuqy.mc.hassium.compression;

import io.github.limuqy.mc.hassium.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 从资源文件加载内置字典
 * <p>
 * 内置字典位于 assets/hassium/ 目录下，使用 .bin 扩展名。
 */
public class ResourceDictionaryLoader {

    private static final String RESOURCE_PATH_PREFIX = "/assets/hassium/";
    private static final String BUILTIN_DICTIONARY_NAME = "hassium-dictionary";
    private static final String DICTIONARY_EXTENSION = ".bin";

    /**
     * 加载所有内置字典
     *
     * @param registry 字典注册表
     * @return 成功加载的字典数量
     */
    public static int loadBuiltinDictionaries(DictionaryRegistry registry) {
        int loadedCount = 0;

        // 加载主字典
        try {
            if (loadBuiltinDictionary(registry, BUILTIN_DICTIONARY_NAME)) {
                loadedCount++;
            }
        } catch (Exception e) {
            Constants.LOG.error("Failed to load builtin dictionary: {}", BUILTIN_DICTIONARY_NAME, e);
        }

        return loadedCount;
    }

    /**
     * 加载指定的内置字典
     *
     * @param registry       字典注册表
     * @param dictionaryName 字典名称（不含扩展名）
     * @return 如果成功加载返回 true
     * @throws CompressionException 加载失败
     */
    public static boolean loadBuiltinDictionary(DictionaryRegistry registry, String dictionaryName)
            throws CompressionException {
        String resourcePath = RESOURCE_PATH_PREFIX + dictionaryName + DICTIONARY_EXTENSION;

        try (InputStream is = ResourceDictionaryLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                Constants.LOG.warn("Builtin dictionary not found: {}", resourcePath);
                return false;
            }

            byte[] dictionaryData = readAllBytes(is);

            if (dictionaryData.length == 0) {
                throw new CompressionException("Empty dictionary file: " + resourcePath);
            }

            // 计算校验和
            long checksum = calculateChecksum(dictionaryData);

            // 创建描述符
            DictionaryDescriptor descriptor = new DictionaryDescriptor(
                    dictionaryName,
                    CompressionAlgorithmId.HASSIUM_ZSTD_DICT,
                    System.currentTimeMillis() / 1000,
                    "1.20.1",
                    0,  // 适用于所有数据版本
                    null,  // 适用于所有维度
                    0,  // 样本数量未知
                    checksum,
                    "builtin"
            );

            // 注册字典
            registry.register(descriptor, dictionaryData);

            Constants.LOG.info("Loaded builtin dictionary: {} ({} bytes, checksum: {})",
                    dictionaryName, dictionaryData.length, checksum);

            return true;
        } catch (IOException e) {
            throw new CompressionException("Failed to read dictionary resource: " + resourcePath, e);
        }
    }

    /**
     * 获取所有可用的内置字典名称
     */
    public static Map<String, String> getBuiltinDictionaries() {
        Map<String, String> dictionaries = new HashMap<>();
        dictionaries.put(BUILTIN_DICTIONARY_NAME, "Default Hassium Dictionary (1.20.1)");
        return dictionaries;
    }

    /**
     * 从输入流读取所有字节
     */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        while ((bytesRead = is.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }

        return baos.toByteArray();
    }

    /**
     * 计算校验和（与 SimpleDictionaryRegistry 使用相同算法）
     */
    private static long calculateChecksum(byte[] data) {
        long hash = 0;
        for (byte b : data) {
            hash = hash * 31 + b;
        }
        return hash;
    }
}
