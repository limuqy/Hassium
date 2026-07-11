package io.github.limuqy.mc.hassium.compression;

import java.util.Optional;

/**
 * 压缩选项
 */
public record CompressionOptions(
        int level,
        Optional<String> dictionaryId,
        boolean verifyChecksum
) {
    /**
     * 默认压缩选项
     */
    public static final CompressionOptions DEFAULT = new CompressionOptions(3, Optional.empty(), true);

    /**
     * 创建指定等级的选项
     */
    public static CompressionOptions withLevel(int level) {
        return new CompressionOptions(level, Optional.empty(), true);
    }

    /**
     * 创建使用字典的选项
     */
    public static CompressionOptions withDictionary(String dictionaryId, int level) {
        return new CompressionOptions(level, Optional.of(dictionaryId), true);
    }

    /**
     * 离线迁移用的高压缩选项
     */
    public static CompressionOptions migration() {
        return new CompressionOptions(9, Optional.empty(), true);
    }
}
