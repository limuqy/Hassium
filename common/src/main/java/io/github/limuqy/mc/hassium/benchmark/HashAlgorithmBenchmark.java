package io.github.limuqy.mc.hassium.benchmark;

import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;

import java.util.Random;

/**
 * Murmur3_64 vs xxHash64 原始字节吞吐对比。
 * <p>
 * 运行：{@code ./gradlew common:runJava -PmainClass=io.github.limuqy.mc.hassium.benchmark.HashAlgorithmBenchmark}
 */
public final class HashAlgorithmBenchmark {

    private static final int WARMUP = 50;
    private static final int ITERATIONS = 500;
    private static final int[] SIZES = {4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024};

    private HashAlgorithmBenchmark() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hassium Hash Algorithm Benchmark (Murmur3_64 vs xxHash64) ===");
        Random random = new Random(42);

        boolean xxAvailable = true;
        try {
            ChunkContentHashUtil.xxHash64OfBytes(new byte[64]);
        } catch (Throwable t) {
            xxAvailable = false;
            System.out.println("xxHash64 unavailable: " + t);
        }

        for (int size : SIZES) {
            byte[] data = new byte[size];
            random.nextBytes(data);

            for (int i = 0; i < WARMUP; i++) {
                ChunkContentHashUtil.murmur3OfBytes(data);
                if (xxAvailable) {
                    ChunkContentHashUtil.xxHash64OfBytes(data);
                }
            }

            long murmurNs = time(() -> ChunkContentHashUtil.murmur3OfBytes(data), ITERATIONS);
            Long xxNs = null;
            if (xxAvailable) {
                xxNs = time(() -> ChunkContentHashUtil.xxHash64OfBytes(data), ITERATIONS);
            }

            double murmurMBs = throughputMBs(size, murmurNs, ITERATIONS);
            System.out.printf("size=%6d B | Murmur3_64: %8.2f MB/s (%d ns/op)",
                    size, murmurMBs, murmurNs / ITERATIONS);
            if (xxNs != null) {
                double xxMBs = throughputMBs(size, xxNs, ITERATIONS);
                System.out.printf(" | xxHash64: %8.2f MB/s (%d ns/op) | winner=%s",
                        xxMBs, xxNs / ITERATIONS, xxMBs > murmurMBs ? "xxHash64" : "Murmur3_64");
            }
            System.out.println();
        }

        System.out.println();
        System.out.println("生产路径已选用 xxHash64（基准显著快于 Murmur3_64）。");
        System.out.println("Murmur3_64 仅保留作对照基准。");
    }

    private static long time(Runnable r, int iterations) {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            r.run();
        }
        return System.nanoTime() - start;
    }

    private static double throughputMBs(int size, long totalNs, int iterations) {
        double totalBytes = (double) size * iterations;
        double seconds = totalNs / 1_000_000_000.0;
        return (totalBytes / (1024.0 * 1024.0)) / seconds;
    }
}
