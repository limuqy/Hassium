package io.github.limuqy.mc.hassium.metrics;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Zlib (level=6) vs ZSTD (level=1,3,6,9,12,16,22) 压缩/解压速度基准。
 * <p>
 * 测量不同大小（128B-2MB）的吞吐量（MB/s），每轮 50 次迭代取中位数。
 * 数据用 MC_STRUCTURED 剖面（~20%随机+~30%零跑+~30%模式+~20%结构重复）。
 */
class ZstdVsZlibSpeedBenchmarkTest {

    private static final int ZLIB_LEVEL = 6;
    private static final int WARMUP = 10;
    private static final int ITERATIONS = 50;

    private static byte[] mcData(int size, long seed) {
        byte[] d = new byte[size];
        Random r = new Random(seed);
        int i = 0;
        while (i < size) {
            int kind = r.nextInt(10);
            if (kind < 2) {
                int run = Math.min(r.nextInt(32) + 1, size - i);
                for (int j = 0; j < run; j++) d[i++] = (byte) r.nextInt(256);
            } else if (kind < 5) {
                int run = Math.min(r.nextInt(256) + 1, size - i);
                i += run;
            } else if (kind < 8) {
                byte val = (byte) r.nextInt(16);
                int run = Math.min(r.nextInt(64) + 1, size - i);
                for (int j = 0; j < run; j++) d[i++] = val;
            } else {
                int patLen = r.nextInt(5) + 4;
                byte[] pat = new byte[patLen];
                r.nextBytes(pat);
                while (i + patLen <= size - 1) {
                    System.arraycopy(pat, 0, d, i, patLen);
                    i += patLen;
                }
                while (i < size) d[i++] = pat[r.nextInt(patLen)];
            }
        }
        return d;
    }

    private static double medianMs(Runnable task, int warmup, int iters) {
        for (int i = 0; i < warmup; i++) task.run();
        long[] times = new long[iters];
        for (int i = 0; i < iters; i++) {
            long start = System.nanoTime();
            task.run();
            times[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(times);
        return times[iters / 2] / 1_000_000.0;
    }

    @Test
    void benchmarkSpeed() {
        int[] sizes = {256, 1024, 4096, 16384, 65536, 262144, 1048576};
        int[] zstdLevels = {1, 3, 6, 9, 12, 16, 22};

        System.out.println("===== 压缩速度 (MB/s, median of " + ITERATIONS + " iters) =====");
        System.out.print("Size     \tZlib6_C\t");
        for (int lv : zstdLevels) System.out.printf("ZSTD%d_C\t", lv);
        System.out.println();

        for (int sz : sizes) {
            byte[] raw = mcData(sz, 42);
            double rawMB = sz / (1024.0 * 1024.0);

            double zlibC = rawMB / (medianMs(() -> {
                Deflater d = new Deflater(ZLIB_LEVEL, false);
                d.setInput(raw);
                d.finish();
                byte[] buf = new byte[sz + 256];
                d.deflate(buf);
                d.end();
            }, WARMUP, ITERATIONS) / 1000.0);

            System.out.printf(Locale.ROOT, "%-10d\t%.1f\t", sz, zlibC);
            for (int lv : zstdLevels) {
                double zstdC = rawMB / (medianMs(() -> Zstd.compress(raw, lv), WARMUP, ITERATIONS) / 1000.0);
                System.out.printf(Locale.ROOT, "%.1f\t", zstdC);
            }
            System.out.println();
        }

        System.out.println();
        System.out.println("===== 解压速度 (MB/s, median of " + ITERATIONS + " iters) =====");
        System.out.print("Size     \tZlib6_D\t");
        for (int lv : zstdLevels) System.out.printf("ZSTD%d_D\t", lv);
        System.out.println();

        for (int sz : sizes) {
            byte[] raw = mcData(sz, 42);
            double rawMB = sz / (1024.0 * 1024.0);

            // Pre-compress
            byte[] zlibCompressed;
            {
                Deflater d = new Deflater(ZLIB_LEVEL, false);
                d.setInput(raw);
                d.finish();
                byte[] buf = new byte[sz + 256];
                int len = d.deflate(buf);
                zlibCompressed = java.util.Arrays.copyOf(buf, len);
                d.end();
            }
            double zlibD = rawMB / (medianMs(() -> {
                Inflater inf = new Inflater(false);
                inf.setInput(zlibCompressed);
                byte[] buf = new byte[sz];
                try { inf.inflate(buf); } catch (Exception e) {}
                inf.end();
            }, WARMUP, ITERATIONS) / 1000.0);

            System.out.printf(Locale.ROOT, "%-10d\t%.1f\t", sz, zlibD);
            for (int lv : zstdLevels) {
                byte[] zstdCompressed = Zstd.compress(raw, lv);
                double zstdD = rawMB / (medianMs(() -> Zstd.decompress(zstdCompressed, sz), WARMUP, ITERATIONS) / 1000.0);
                System.out.printf(Locale.ROOT, "%.1f\t", zstdD);
            }
            System.out.println();
        }
    }
}