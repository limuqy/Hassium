package io.github.limuqy.mc.hassium.benchmark;

import com.github.luben.zstd.Zstd;

import java.util.Random;

/**
 * ZSTD 压缩级别基准测试
 * <p>
 * 测试不同压缩级别（3/6/9）的压缩比和吞吐量，找到最佳平衡点。
 * <p>
 * 运行：{@code ./gradlew common:runJava -PmainClass=io.github.limuqy.mc.hassium.benchmark.CompressionBenchmark}
 */
public final class CompressionBenchmark {

    private static final int WARMUP = 20;
    private static final int ITERATIONS = 100;
    private static final int[] LEVELS = {1, 3, 6, 9, 12};
    private static final int[] SIZES = {4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024};

    private CompressionBenchmark() {}

    public static void main(String[] args) {
        System.out.println("=== Hassium ZSTD Compression Benchmark ===");
        System.out.println("测试不同压缩级别的压缩比和吞吐量");
        System.out.println();

        Random random = new Random(42);
        // review-fix: T9-39 按实测累计各 level 的压缩比/压缩速度，末尾推荐基于实测数据计算而非硬编码
        double[] sumRatio = new double[LEVELS.length];
        double[] sumCompressSpeed = new double[LEVELS.length];
        int levelCount = 0;

        for (int size : SIZES) {
            byte[] data = generateTestData(random, size);

            System.out.printf("--- 数据大小: %d KB ---%n", size / 1024);
            System.out.printf("%-8s %-12s %-12s %-12s %-12s%n", 
                    "Level", "压缩比", "压缩速度", "解压速度", "压缩耗时");
            System.out.println("-".repeat(60));

            for (int li = 0; li < LEVELS.length; li++) {
                int level = LEVELS[li];
                // 预热
                for (int i = 0; i < WARMUP; i++) {
                    Zstd.compress(data, level);
                }

                // 测试压缩
                long compressStart = System.nanoTime();
                byte[] compressed = null;
                for (int i = 0; i < ITERATIONS; i++) {
                    compressed = Zstd.compress(data, level);
                }
                long compressTime = System.nanoTime() - compressStart;

                // 测试解压
                long decompressStart = System.nanoTime();
                for (int i = 0; i < ITERATIONS; i++) {
                    Zstd.decompress(compressed, (int) Zstd.getFrameContentSize(compressed));
                }
                long decompressTime = System.nanoTime() - decompressStart;

                // 计算指标
                double ratio = (double) data.length / compressed.length;
                double compressSpeed = throughputMBs(data.length, compressTime, ITERATIONS);
                double decompressSpeed = throughputMBs(data.length, decompressTime, ITERATIONS);
                double compressMs = (compressTime / 1_000_000.0) / ITERATIONS;
                sumRatio[li] += ratio;
                sumCompressSpeed[li] += compressSpeed;

                System.out.printf("%-8d %-12.2f %-12.2f %-12.2f %-12.3f%n",
                        level, ratio, compressSpeed, decompressSpeed, compressMs);
            }
            levelCount++;
            System.out.println();
        }

        // 推荐：按实测均值计算，避免硬编码结论与本次数据相矛盾
        System.out.println("=== 推荐分析（按本次实测均值） ===");
        int bestRatio = 0, bestSpeed = 0;
        for (int i = 1; i < LEVELS.length; i++) {
            if (sumRatio[i] / levelCount > sumRatio[bestRatio] / levelCount) {
                bestRatio = i;
            }
            if (sumCompressSpeed[i] / levelCount > sumCompressSpeed[bestSpeed] / levelCount) {
                bestSpeed = i;
            }
        }
        // 平衡点：压缩比不低于最优 95% 的 level 中压缩速度最快者
        int balanced = bestSpeed;
        double bestRatioVal = sumRatio[bestRatio] / levelCount;
        for (int i = 0; i < LEVELS.length; i++) {
            if (sumRatio[i] / levelCount >= bestRatioVal * 0.95
                    && sumCompressSpeed[i] / levelCount > sumCompressSpeed[balanced] / levelCount) {
                balanced = i;
            }
        }
        System.out.printf("  压缩比最高: Level %d（平均 %.2f:1）%n",
                LEVELS[bestRatio], sumRatio[bestRatio] / levelCount);
        System.out.printf("  压缩速度最高: Level %d（平均 %.1f MB/s）%n",
                LEVELS[bestSpeed], sumCompressSpeed[bestSpeed] / levelCount);
        System.out.printf("  平衡推荐: Level %d（压缩比 ≥ 最优 95%% 中速度最快）%n", LEVELS[balanced]);
    }

    /**
     * 生成测试数据（模拟 NBT 数据特征）
     */
    private static byte[] generateTestData(Random random, int size) {
        byte[] data = new byte[size];
        // 模拟 NBT 数据：大量重复模式 + 少量随机数据
        int pos = 0;
        while (pos < size) {
            // 重复模式（模拟方块 ID 重复）
            int repeatLen = Math.min(64, size - pos);
            byte pattern = (byte) random.nextInt(256);
            for (int i = 0; i < repeatLen; i++) {
                data[pos++] = pattern;
            }
            // 随机数据（模拟坐标、元数据）
            int randomLen = Math.min(16, size - pos);
            for (int i = 0; i < randomLen; i++) {
                data[pos++] = (byte) random.nextInt(256);
            }
        }
        return data;
    }

    private static double throughputMBs(int size, long totalNs, int iterations) {
        double totalBytes = (double) size * iterations;
        double seconds = totalNs / 1_000_000_000.0;
        return (totalBytes / (1024.0 * 1024.0)) / seconds;
    }
}
