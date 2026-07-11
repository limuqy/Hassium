package io.github.limuqy.mc.hassium.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 样本数量对字典质量影响的基准测试
 * <p>
 * 使用相同的样本池，测试不同样本数量对字典训练效果的影响，
 * 输出压缩率、训练时间等指标的对比报告。
 */
public class SampleSizeBenchmark {

    public record BenchmarkConfig(
            Path worldSaveDir,
            int maxSamples,
            int dictionarySize,
            int[] testSampleCounts
    ) {
        public static final BenchmarkConfig DEFAULT = new BenchmarkConfig(
                null,
                10000,
                112640,
                new int[]{100, 200, 500, 1000, 2000, 5000}
        );
    }

    public record BenchmarkPoint(
            int sampleCount,
            long trainingTimeMs,
            double compressionRatioWithoutDict,
            double compressionRatioWithDict,
            double improvement
    ) {
        public String toRow() {
            return String.format("%-12d %-15d %-25.2f%% %-25.2f%% %-15.2f%%",
                    sampleCount,
                    trainingTimeMs,
                    compressionRatioWithoutDict,
                    compressionRatioWithDict,
                    improvement
            );
        }
    }

    public static class BenchmarkReport {
        private final List<BenchmarkPoint> points = new ArrayList<>();

        public void addPoint(BenchmarkPoint point) {
            points.add(point);
        }

        public void print() {
            System.out.println();
            System.out.println("=".repeat(110));
            System.out.println("样本数量基准测试报告");
            System.out.println("=".repeat(110));
            System.out.println(String.format("%-12s %-15s %-25s %-25s %-15s",
                    "样本数", "训练时间(ms)", "无字典压缩率", "有字典压缩率", "压缩率提升"));
            System.out.println("-".repeat(110));

            for (BenchmarkPoint point : points) {
                System.out.println(point.toRow());
            }

            System.out.println("=".repeat(110));
            System.out.println();

            // 分析结论
            analyzeResults();
        }

        private void analyzeResults() {
            if (points.isEmpty()) {
                return;
            }

            System.out.println("📊 分析结论:");
            System.out.println();

            // 找到最佳性价比点（压缩率提升 vs 训练时间）
            double bestEfficiency = 0;
            BenchmarkPoint bestPoint = points.get(0);

            for (BenchmarkPoint point : points) {
                // 效率 = 压缩率提升 / 训练时间（秒）
                double efficiency = point.improvement() / (point.trainingTimeMs() / 1000.0);
                if (efficiency > bestEfficiency) {
                    bestEfficiency = efficiency;
                    bestPoint = point;
                }
            }

            System.out.printf("  ✅ 最佳性价比: %d 个样本 (压缩率提升 %.2f%%, 训练时间 %d ms)%n",
                    bestPoint.sampleCount(),
                    bestPoint.improvement(),
                    bestPoint.trainingTimeMs()
            );

            // 边际收益分析
            System.out.println();
            System.out.println("  📈 边际收益分析:");
            for (int i = 1; i < points.size(); i++) {
                BenchmarkPoint prev = points.get(i - 1);
                BenchmarkPoint curr = points.get(i);

                double improvementGain = curr.improvement() - prev.improvement();
                double timeIncrease = curr.trainingTimeMs() - prev.trainingTimeMs();
                int sampleIncrease = curr.sampleCount() - prev.sampleCount();

                System.out.printf("    %d → %d 样本: 压缩率提升 +%.2f%%, 时间增加 +%d ms (+%.1f%%)%n",
                        prev.sampleCount(),
                        curr.sampleCount(),
                        improvementGain,
                        (long) timeIncrease,
                        timeIncrease / prev.trainingTimeMs() * 100
                );

                // 标记边际收益递减点
                if (i > 1) {
                    BenchmarkPoint prevPrev = points.get(i - 2);
                    double prevGain = prev.improvement() - prevPrev.improvement();
                    if (improvementGain < prevGain * 0.5) {
                        System.out.printf("      ⚠️  边际收益明显递减 (收益减半)%n");
                    }
                }
            }

            // 推荐建议
            System.out.println();
            System.out.println("  💡 推荐建议:");
            BenchmarkPoint last = points.get(points.size() - 1);
            if (bestPoint.sampleCount() < last.sampleCount() * 0.5) {
                System.out.printf("    - 生产环境推荐: %d 样本（性价比最高）%n", bestPoint.sampleCount());
                System.out.printf("    - 追求极致压缩: %d 样本（最高压缩率，但边际收益低）%n", last.sampleCount());
            } else {
                System.out.printf("    - 样本数越多效果越好，建议使用 %d 样本%n", last.sampleCount());
            }
        }
    }

    /**
     * 运行基准测试
     */
    public static BenchmarkReport runBenchmark(BenchmarkConfig config) throws IOException {
        System.out.println("样本数量基准测试");
        System.out.println("字典大小: " + config.dictionarySize() + " bytes");
        System.out.println();

        // 1. 提取完整样本池
        System.out.printf("步骤 1/2: 从存档提取样本池 (最多 %d 个样本)...%n", config.maxSamples());
        List<byte[]> fullSamplePool;
        if (config.worldSaveDir() != null) {
            fullSamplePool = DictionaryTrainer.extractRealChunkSamples(
                    config.worldSaveDir().resolve("region"),
                    config.maxSamples(),
                    null
            );
        } else {
            fullSamplePool = DictionaryTrainer.generateTrainingSamples(
                    new DictionaryTrainer.TrainingParams(
                            config.dictionarySize(),
                            1024,
                            config.maxSamples()
                    )
            );
        }
        System.out.printf("  提取到 %d 个样本，总大小: %.2f MB%n",
                fullSamplePool.size(),
                fullSamplePool.stream().mapToLong(s -> s.length).sum() / 1024.0 / 1024.0
        );
        System.out.println();

        // 2. 测试不同样本数量
        System.out.println("步骤 2/2: 测试不同样本数量的训练效果...");
        System.out.println();

        BenchmarkReport report = new BenchmarkReport();

        for (int sampleCount : config.testSampleCounts()) {
            if (sampleCount > fullSamplePool.size()) {
                System.out.printf("  跳过 %d 样本（样本池不足）%n", sampleCount);
                continue;
            }

            System.out.printf("  测试 %d 个样本...%n", sampleCount);

            // 使用前 N 个样本训练
            List<byte[]> subset = fullSamplePool.subList(0, sampleCount);
            DictionaryTrainer.TrainingResult result = DictionaryTrainer.trainDictionaryFromSamples(
                    subset,
                    config.dictionarySize()
            );

            BenchmarkPoint point = new BenchmarkPoint(
                    sampleCount,
                    result.trainingTimeMs(),
                    result.compressionRatioWithoutDict(),
                    result.compressionRatioWithDict(),
                    result.improvementPercent()
            );
            report.addPoint(point);

            System.out.printf("    训练时间: %d ms, 压缩率提升: %.2f%%%n",
                    result.trainingTimeMs(),
                    result.improvementPercent()
            );
        }

        return report;
    }

    public static void main(String[] args) {
        try {
            BenchmarkConfig config = BenchmarkConfig.DEFAULT;

            // 解析参数
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--world":
                        if (i + 1 < args.length) {
                            Path worldDir = Path.of(args[++i]);
                            config = new BenchmarkConfig(
                                    worldDir,
                                    config.maxSamples(),
                                    config.dictionarySize(),
                                    config.testSampleCounts()
                            );
                        }
                        break;
                    case "--max-samples":
                        if (i + 1 < args.length) {
                            int max = Integer.parseInt(args[++i]);
                            config = new BenchmarkConfig(
                                    config.worldSaveDir(),
                                    max,
                                    config.dictionarySize(),
                                    config.testSampleCounts()
                            );
                        }
                        break;
                    case "--dict-size":
                        if (i + 1 < args.length) {
                            int size = Integer.parseInt(args[++i]);
                            config = new BenchmarkConfig(
                                    config.worldSaveDir(),
                                    config.maxSamples(),
                                    size,
                                    config.testSampleCounts()
                            );
                        }
                        break;
                    case "--test-counts":
                        if (i + 1 < args.length) {
                            String[] parts = args[++i].split(",");
                            int[] counts = new int[parts.length];
                            for (int j = 0; j < parts.length; j++) {
                                counts[j] = Integer.parseInt(parts[j].trim());
                            }
                            config = new BenchmarkConfig(
                                    config.worldSaveDir(),
                                    config.maxSamples(),
                                    config.dictionarySize(),
                                    counts
                            );
                        }
                        break;
                    case "--help":
                        printUsage();
                        return;
                }
            }

            // 运行基准测试
            BenchmarkReport report = runBenchmark(config);
            report.print();

        } catch (Exception e) {
            System.err.println("基准测试失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("用法: SampleSizeBenchmark [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --world <存档根目录>     使用真实存档数据（推荐）");
        System.out.println("  --max-samples <count>    样本池最大大小（默认: 10000）");
        System.out.println("  --dict-size <size>       字典大小（默认: 112640）");
        System.out.println("  --test-counts <list>     测试的样本数量列表，逗号分隔（默认: 100,200,500,1000,2000,5000）");
        System.out.println("  --help                   显示帮助信息");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java SampleSizeBenchmark --world \"D:\\\\MC\\\\saves\\\\MyWorld\" --test-counts 500,1000,2000,5000,10000");
    }
}
