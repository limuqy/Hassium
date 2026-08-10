package io.github.limuqy.mc.hassium.compression;

import java.util.Optional;

/**
 * 压缩选项
 * <p>
 * {@code verifyChecksum} 为遗留死配置（review-fix: T5-92）：全部内置 codec 由压缩格式自身
 * 保证完整性（zstd 帧内嵌内容校验、zlib Adler-32），解压失败即抛
 * {@link CompressionException.DecompressionFailedException}，该标志无消费方、无额外校验可实现；
 * 移除将破坏公开 record 签名，故保留键并标记 {@code @Deprecated}——禁止新增消费方。
 */
public record CompressionOptions(
        int level,
        Optional<String> dictionaryId,
        @Deprecated boolean verifyChecksum
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
