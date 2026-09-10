package io.github.limuqy.mc.hassium.compression;

import java.util.Optional;

/**
 * 压缩选项
 * <p>
 * 完整性由压缩格式自身保证（zstd 帧内嵌内容校验、zlib Adler-32），解压失败即抛
 * {@link CompressionException.DecompressionFailedException}，无需额外校验标志。
 */
public record CompressionOptions(
        int level,
        Optional<String> dictionaryId
) {
    /**
     * 默认压缩选项
     */
    public static final CompressionOptions DEFAULT = new CompressionOptions(3, Optional.empty());

    /**
     * 创建指定等级的选项
     */
    public static CompressionOptions withLevel(int level) {
        return new CompressionOptions(level, Optional.empty());
    }

    /**
     * 创建使用字典的选项
     */
    public static CompressionOptions withDictionary(String dictionaryId, int level) {
        return new CompressionOptions(level, Optional.of(dictionaryId));
    }

    /**
     * 离线迁移用的高压缩选项
     */
    public static CompressionOptions migration() {
        return new CompressionOptions(9, Optional.empty());
    }
}
