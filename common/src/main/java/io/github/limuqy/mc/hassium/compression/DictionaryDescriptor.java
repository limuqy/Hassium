package io.github.limuqy.mc.hassium.compression;

/**
 * 字典描述符
 */
public record DictionaryDescriptor(
        String dictionaryId,
        CompressionAlgorithmId algorithmId,
        long createdAt,
        String minecraftVersion,
        int dataVersion,
        String dimension,
        int sampleCount,
        long dictionaryChecksum,
        String sourceProfile
) {
    /**
     * 检查字典是否适用于指定维度
     */
    public boolean isApplicableFor(String dimension) {
        return this.dimension == null || this.dimension.equals(dimension);
    }

    /**
     * 检查字典是否适用于指定数据版本
     */
    public boolean isApplicableForDataVersion(int dataVersion) {
        return this.dataVersion == 0 || this.dataVersion == dataVersion;
    }

    /**
     * 获取字典文件名
     */
    public String getFileName() {
        return dictionaryId + ".dict";
    }
}
