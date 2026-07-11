package io.github.limuqy.mc.hassium.migration;

import java.util.List;

/**
 * 迁移结果
 */
public record MigrationResult(
        boolean success,
        int totalChunks,
        int migratedChunks,
        int skippedChunks,
        int failedChunks,
        long totalBytesOriginal,
        long totalBytesMigrated,
        long migrationTimeMs,
        List<String> errors
) {
    /**
     * 创建成功结果
     */
    public static MigrationResult success(
            int totalChunks,
            int migratedChunks,
            int skippedChunks,
            long totalBytesOriginal,
            long totalBytesMigrated,
            long migrationTimeMs
    ) {
        return new MigrationResult(
                true,
                totalChunks,
                migratedChunks,
                skippedChunks,
                0,
                totalBytesOriginal,
                totalBytesMigrated,
                migrationTimeMs,
                List.of()
        );
    }

    /**
     * 创建失败结果
     */
    public static MigrationResult failure(
            int totalChunks,
            int migratedChunks,
            int failedChunks,
            List<String> errors
    ) {
        return new MigrationResult(
                false,
                totalChunks,
                migratedChunks,
                0,
                failedChunks,
                0,
                0,
                0,
                errors
        );
    }

    /**
     * 计算压缩率
     */
    public double compressionRatio() {
        if (totalBytesOriginal == 0) return 1.0;
        return (double) totalBytesMigrated / totalBytesOriginal;
    }

    /**
     * 计算空间节省率
     */
    public double spaceSavingRatio() {
        return 1.0 - compressionRatio();
    }

    /**
     * 获取成功率
     */
    public double successRate() {
        if (totalChunks == 0) return 1.0;
        return (double) migratedChunks / totalChunks;
    }
}
