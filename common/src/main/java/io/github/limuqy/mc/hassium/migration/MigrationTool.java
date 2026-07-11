package io.github.limuqy.mc.hassium.migration;

import java.nio.file.Path;
import java.util.List;

/**
 * 迁移工具接口
 */
public interface MigrationTool {

    /**
     * 执行迁移
     *
     * @param worldDir 世界目录
     * @param options  迁移选项
     * @return 迁移结果
     * @throws MigrationException 迁移失败
     */
    MigrationResult migrate(Path worldDir, MigrationOptions options) throws MigrationException;

    /**
     * 验证迁移结果
     *
     * @param worldDir 世界目录
     * @return 验证结果
     * @throws MigrationException 验证失败
     */
    ValidationResult validate(Path worldDir) throws MigrationException;

    /**
     * 回滚迁移
     *
     * @param worldDir 世界目录
     * @return 回滚结果
     * @throws MigrationException 回滚失败
     */
     MigrationResult rollback(Path worldDir) throws MigrationException;

    /**
     * 迁移选项
     */
    record MigrationOptions(
            List<String> dimensions,
            int regionXMin,
            int regionXMax,
            int regionZMin,
            int regionZMax,
            int maxChunks,
            String targetAlgorithm,
            int compressionLevel,
            boolean dryRun
    ) {
        /**
         * 默认迁移选项
         */
        public static final MigrationOptions DEFAULT = new MigrationOptions(
                List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                "hassium:zstd",
                3,
                false
        );

        /**
         * 创建试运行选项
         */
        public static MigrationOptions createDryRun() {
            return new MigrationOptions(
                    DEFAULT.dimensions(),
                    DEFAULT.regionXMin(),
                    DEFAULT.regionXMax(),
                    DEFAULT.regionZMin(),
                    DEFAULT.regionZMax(),
                    DEFAULT.maxChunks(),
                    DEFAULT.targetAlgorithm(),
                    DEFAULT.compressionLevel(),
                    true
            );
        }
    }

    /**
     * 验证结果
     */
    record ValidationResult(
            boolean valid,
            int totalRegions,
            int totalChunks,
            int vanillaChunks,
            int hassiumChunks,
            int corruptedChunks,
            List<String> issues
    ) {
        /**
         * 创建有效结果
         */
        public static ValidationResult valid(int totalRegions, int totalChunks, int vanillaChunks, int hassiumChunks) {
            return new ValidationResult(
                    true,
                    totalRegions,
                    totalChunks,
                    vanillaChunks,
                    hassiumChunks,
                    0,
                    List.of()
            );
        }

        /**
         * 创建无效结果
         */
        public static ValidationResult invalid(List<String> issues) {
            return new ValidationResult(
                    false,
                    0,
                    0,
                    0,
                    0,
                    0,
                    issues
            );
        }
    }
}
