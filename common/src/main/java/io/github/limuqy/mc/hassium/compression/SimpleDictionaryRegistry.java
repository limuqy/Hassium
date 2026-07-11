package io.github.limuqy.mc.hassium.compression;

import io.github.limuqy.mc.hassium.Constants;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的字典注册表实现
 */
public class SimpleDictionaryRegistry implements DictionaryRegistry {

    private final Map<String, byte[]> dictionaries = new ConcurrentHashMap<>();
    private final Map<String, DictionaryDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public void register(DictionaryDescriptor descriptor, byte[] data) throws CompressionException {
        String id = descriptor.dictionaryId();

        // 检查是否已存在
        if (descriptors.containsKey(id)) {
            throw new CompressionException("Dictionary already registered: " + id);
        }

        // 校验数据
        long checksum = calculateChecksum(data);
        if (checksum != descriptor.dictionaryChecksum()) {
            throw new CompressionException.ChecksumMismatchException(descriptor.dictionaryChecksum(), checksum);
        }

        dictionaries.put(id, data);
        descriptors.put(id, descriptor);

        Constants.LOG.info("Registered dictionary: {} ({} bytes)", id, data.length);
    }

    @Override
    public Optional<byte[]> findDictionary(String dictionaryId) {
        return Optional.ofNullable(dictionaries.get(dictionaryId));
    }

    @Override
    public Optional<DictionaryDescriptor> getDescriptor(String dictionaryId) {
        return Optional.ofNullable(descriptors.get(dictionaryId));
    }

    @Override
    public boolean hasDictionary(String dictionaryId) {
        return dictionaries.containsKey(dictionaryId);
    }

    @Override
    public boolean verify(String dictionaryId) throws CompressionException {
        byte[] data = dictionaries.get(dictionaryId);
        DictionaryDescriptor descriptor = descriptors.get(dictionaryId);

        if (data == null || descriptor == null) {
            return false;
        }

        long checksum = calculateChecksum(data);
        return checksum == descriptor.dictionaryChecksum();
    }

    @Override
    public Collection<DictionaryDescriptor> getAllDescriptors() {
        return Collections.unmodifiableCollection(descriptors.values());
    }

    @Override
    public void loadFromDirectory(Path directory) throws CompressionException {
        if (!Files.isDirectory(directory)) {
            throw new CompressionException("Not a directory: " + directory);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.dict")) {
            for (Path dictFile : stream) {
                try {
                    loadDictionaryFile(dictFile);
                } catch (Exception e) {
                    Constants.LOG.error("Failed to load dictionary: {}", dictFile, e);
                }
            }
        } catch (IOException e) {
            throw new CompressionException("Failed to scan dictionary directory", e);
        }
    }

    @Override
    public boolean unregister(String dictionaryId) {
        dictionaries.remove(dictionaryId);
        DictionaryDescriptor removed = descriptors.remove(dictionaryId);
        if (removed != null) {
            Constants.LOG.info("Unregistered dictionary: {}", dictionaryId);
            return true;
        }
        return false;
    }

    /**
     * 加载单个字典文件
     */
    private void loadDictionaryFile(Path dictFile) throws IOException, CompressionException {
        String fileName = dictFile.getFileName().toString();
        String dictionaryId = fileName.replace(".dict", "");

        byte[] data = Files.readAllBytes(dictFile);
        long checksum = calculateChecksum(data);

        // 创建默认描述符
        DictionaryDescriptor descriptor = new DictionaryDescriptor(
                dictionaryId,
                CompressionAlgorithmId.HASSIUM_ZSTD_DICT,
                System.currentTimeMillis() / 1000,
                "1.20.1",
                0,
                null,
                0,
                checksum,
                "file"
        );

        dictionaries.put(dictionaryId, data);
        descriptors.put(dictionaryId, descriptor);

        Constants.LOG.info("Loaded dictionary from file: {} ({} bytes)", dictionaryId, data.length);
    }

    /**
     * 计算校验和（简单实现，建议使用 XXHash64 或 CRC32C）
     */
    private long calculateChecksum(byte[] data) {
        // 简单的校验和实现，生产环境应使用 XXHash64 或 CRC32C
        long hash = 0;
        for (byte b : data) {
            hash = hash * 31 + b;
        }
        return hash;
    }
}
