package io.github.limuqy.mc.hassium.metrics;

/**
 * 压缩统计信息
 */
public record CompressionStats(
        long totalCompressed,
        long totalUncompressed,
        long compressTimeNs,
        long decompressTimeNs,
        long compressCount,
        long decompressCount,
        long errors
) {
    /**
     * 创建空统计
     */
    public static CompressionStats empty() {
        return new CompressionStats(0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * 计算压缩率
     */
    public double compressionRatio() {
        if (totalUncompressed == 0) return 1.0;
        return (double) totalCompressed / totalUncompressed;
    }

    /**
     * 计算空间节省率
     */
    public double spaceSavingRatio() {
        return 1.0 - compressionRatio();
    }

    /**
     * 计算平均压缩耗时（毫秒）
     */
    public double averageCompressTimeMs() {
        if (compressCount == 0) return 0.0;
        return (compressTimeNs / 1_000_000.0) / compressCount;
    }

    /**
     * 计算平均解压耗时（毫秒）
     */
    public double averageDecompressTimeMs() {
        if (decompressCount == 0) return 0.0;
        return (decompressTimeNs / 1_000_000.0) / decompressCount;
    }

    /**
     * 合并统计信息
     */
    public CompressionStats merge(CompressionStats other) {
        return new CompressionStats(
                this.totalCompressed + other.totalCompressed,
                this.totalUncompressed + other.totalUncompressed,
                this.compressTimeNs + other.compressTimeNs,
                this.decompressTimeNs + other.decompressTimeNs,
                this.compressCount + other.compressCount,
                this.decompressCount + other.decompressCount,
                this.errors + other.errors
        );
    }
}
