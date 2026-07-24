package io.github.limuqy.mc.hassium.metrics;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * Zlib (level=6, MC default) vs ZSTD (level=3, Hassium default) 压缩对比。
 * <p>
 * 用类 MC 区块包体（随机/稀疏 byte[]）在不同大小下的压缩率和产出大小。
 * 同时覆盖小包聚合场景：N 个小包聚合后整体压缩 vs N 个独立压缩。
 * <p>
 * 参数：
 * <ul>
 *   <li>Zlib: Deflater.DEFAULT_COMPRESSION (6), threshold=256, nowrap=false</li>
 *   <li>ZSTD: compress(byte[], 3), threshold=256</li>
 *   <li>MC 帧格式: VarInt(uncompressedLen) + compressed bytes; &lt;256 → VarInt(0) + raw</li>
 * </ul>
 *
 * @see io.github.limuqy.mc.hassium.compression.VanillaZlibCodec
 * @see io.github.limuqy.mc.hassium.compression.ZstdCompressionCodec
 */
class VanillaZlibVsZstdBenchmarkTest {

    private static final int MC_COMPRESSION_THRESHOLD = 256;
    private static final int ZLIB_LEVEL = Deflater.DEFAULT_COMPRESSION; // 6
    private static final int ZSTD_LEVEL = 3;

    // ── 不同的数据剖面 ──
    private enum Profile {
        /** 均匀随机字节（真正不可压缩） */
        RANDOM,
        /** 50% 零字节（模拟空气方块多的平坦地形） */
        SPARSE,
        /**
         * 模拟 MC 区块包体结构：~20% 随机 + ~30% 零跑(空气) +
         * ~30% 重复短模式(调色板索引) + ~20% 结构化重复(NBT 模式)。
         * 这与真实区块 Zlib 压缩率（20-35%）最接近。
         */
        MC_STRUCTURED
    }

    private static byte[] data(int size, long seed, Profile profile) {
        byte[] d = new byte[size];
        Random r = new Random(seed);
        if (profile == Profile.SPARSE) {
            for (int i = 0; i < size; i++) d[i] = r.nextInt(3) == 0 ? (byte) r.nextInt(256) : 0;
        } else if (profile == Profile.RANDOM) {
            r.nextBytes(d);
        } else {
            // MC_NBT: 模拟 MC 区块包体 —— 块调色板 + 短零跑 + NBT 标签模式
            // ~20% 随机字节 (真正不可压缩), ~30% 零跑 (空气), ~50% 重复结构 (调色板/NBT 模式)
            int i = 0;
            while (i < size) {
                int runKind = r.nextInt(10);
                if (runKind < 2) {
                    // 20%: 完全随机（不可压缩）
                    int run = Math.min(r.nextInt(32) + 1, size - i);
                    for (int j = 0; j < run; j++) d[i++] = (byte) r.nextInt(256);
                } else if (runKind < 5) {
                    // 30%: 长零跑（空气方块）
                    int run = Math.min(r.nextInt(256) + 1, size - i);
                    i += run; // 默认已是 0
                } else if (runKind < 8) {
                    // 30%: 重复短模式（调色板索引、低位字节）
                    byte val = (byte) r.nextInt(16);
                    int run = Math.min(r.nextInt(64) + 1, size - i);
                    for (int j = 0; j < run; j++) d[i++] = val;
                } else {
                    // 20%: 重复的 4-8 字节模式（NBT tag 头、坐标等）
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
        }
        return d;
    }

    // ── helpers ──

    private static int varIntBytes(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) { n++; v >>>= 7; }
        return n;
    }

    private static int frameZlib(byte[] raw) {
        if (raw.length < MC_COMPRESSION_THRESHOLD) {
            return varIntBytes(0) + raw.length;
        }
        Deflater d = new Deflater(ZLIB_LEVEL, false);
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[raw.length + 64];
        int clen = d.deflate(buf);
        d.end();
        return varIntBytes(raw.length) + clen;
    }

    private static int frameZstd(byte[] raw) {
        if (raw.length < MC_COMPRESSION_THRESHOLD) {
            return varIntBytes(0) + raw.length;
        }
        byte[] c = Zstd.compress(raw, ZSTD_LEVEL);
        return varIntBytes(raw.length) + c.length;
    }

    // ── tests ──

    @Test
    void benchmarkSinglePackets() {
        int[] sizes = {128, 256, 512, 1024, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576, 2097152};
        System.out.println("===== 单包对比 (Profile=RANDOM) =====");
        System.out.println("Size     \tZlib-out\tZlib%\tZSTD-out\tZSTD%\tZSTD win%");
        for (int sz : sizes) {
            byte[] raw = data(sz, 42 + sz, Profile.RANDOM);
            int zlib = frameZlib(raw);
            int zstd = frameZstd(raw);
            double zlibRate = (double) zlib / sz * 100.0;
            double zstdRate = (double) zstd / sz * 100.0;
            double win = (double)(zlib - zstd) / zlib * 100.0;
            System.out.printf(Locale.ROOT, "%-10d\t%-10d\t%.1f%%\t%-10d\t%.1f%%\t%.1f%%%n",
                    sz, zlib, zlibRate, zstd, zstdRate, win);
        }

        System.out.println();
        System.out.println("===== 单包对比 (Profile=SPARSE, 50%零字节) =====");
        System.out.println("Size     \tZlib-out\tZlib%\tZSTD-out\tZSTD%\tZSTD win%");
        for (int sz : sizes) {
            byte[] raw = data(sz, 42 + sz, Profile.SPARSE);
            int zlib = frameZlib(raw);
            int zstd = frameZstd(raw);
            double zlibRate = (double) zlib / sz * 100.0;
            double zstdRate = (double) zstd / sz * 100.0;
            double win = (double)(zlib - zstd) / zlib * 100.0;
            System.out.printf(Locale.ROOT, "%-10d\t%-10d\t%.1f%%\t%-10d\t%.1f%%\t%.1f%%%n",
                    sz, zlib, zlibRate, zstd, zstdRate, win);
        }

        System.out.println();
        System.out.println("===== 单包对比 (Profile=MC_STRUCTURED, 模拟NBT区块) =====");
        System.out.println("Size     \tZlib-out\tZlib%\tZSTD-out\tZSTD%\tZSTD win%");
        for (int sz : sizes) {
            byte[] raw = data(sz, 42 + sz, Profile.MC_STRUCTURED);
            int zlib = frameZlib(raw);
            int zstd = frameZstd(raw);
            double zlibRate = (double) zlib / sz * 100.0;
            double zstdRate = (double) zstd / sz * 100.0;
            double win = (double)(zlib - zstd) / zlib * 100.0;
            System.out.printf(Locale.ROOT, "%-10d\t%-10d\t%.1f%%\t%-10d\t%.1f%%\t%.1f%%%n",
                    sz, zlib, zlibRate, zstd, zstdRate, win);
        }
    }

    @Test
    void benchmarkBelowThreshold() {
        // 低于 256 的包：两者都不压缩，仅 VarInt(0) 帧头
        int[] sizes = {1, 16, 64, 128, 200, 255};
        System.out.println("===== 低于阈值包（均不压缩）=====");
        System.out.println("Size\tFrameSize(=raw+VarInt(0))");
        for (int sz : sizes) {
            System.out.printf("%d\t%d%n", sz, sz + varIntBytes(0));
        }
    }

    @Test
    void benchmarkAggregationEffect() {
        // 模拟聚合效果：N 个小包聚合后整体压缩 vs N 个独立压缩
        // 小包大小取 64-512 字节（典型 MC 非区块包：Entity, BlockUpdate, etc.）
        long seed = 99;
        int[] smallSizes = {64, 128, 200, 256, 350, 512};
        int[] batchSizes = {4, 8, 16};

        for (int n : batchSizes) {
            System.out.printf("===== 聚合效果: %d 小包 (RANDOM) =====", n);
            // 每个包大小随机取（从 smallSizes 数组中循环）
            int[] sizes = new int[n];
            byte[][] packets = new byte[n][];
            int totalRaw = 0;
            for (int i = 0; i < n; i++) {
                sizes[i] = smallSizes[i % smallSizes.length];
                packets[i] = data(sizes[i], seed + i * 1000, Profile.RANDOM);
                totalRaw += sizes[i];
            }

            // 方案 A：各自独立压缩（小包低于 256 阈值的直接不压缩，VarInt(0)+raw）
            int independentTotal = 0;
            for (int i = 0; i < n; i++) {
                independentTotal += frameZlib(packets[i]);
            }

            // 方案 B：聚合后整体 ZSTD
            byte[] merged = new byte[totalRaw];
            int off = 0;
            for (int i = 0; i < n; i++) {
                System.arraycopy(packets[i], 0, merged, off, packets[i].length);
                off += packets[i].length;
            }
            int aggregatedZstd = frameZstd(merged);

            // 方案 C：聚合后整体 Zlib（原版不聚合，这里仅参考）
            int aggregatedZlib = frameZlib(merged);

            double savingVsIndependent = (double)(independentTotal - aggregatedZstd) / independentTotal * 100.0;
            System.out.printf("独立 Zlib: %d, 聚合 ZSTD: %d, 节省: %.1f%% (聚合 Zlib: %d)%n",
                    independentTotal, aggregatedZstd, savingVsIndependent, aggregatedZlib);
        }

        System.out.println();
        for (int n : batchSizes) {
            System.out.printf("===== 聚合效果: %d 小包 (MC_STRUCTURED) =====%n", n);
            int[] sizes = new int[n];
            byte[][] packets = new byte[n][];
            int totalRaw = 0;
            for (int i = 0; i < n; i++) {
                sizes[i] = smallSizes[i % smallSizes.length];
                packets[i] = data(sizes[i], seed + i * 1000, Profile.MC_STRUCTURED);
                totalRaw += sizes[i];
            }

            int independentTotal = 0;
            for (int i = 0; i < n; i++) {
                independentTotal += frameZlib(packets[i]);
            }

            byte[] merged = new byte[totalRaw];
            int off = 0;
            for (int i = 0; i < n; i++) {
                System.arraycopy(packets[i], 0, merged, off, packets[i].length);
                off += packets[i].length;
            }
            int aggregatedZstd = frameZstd(merged);
            int aggregatedZlib = frameZlib(merged);

            double savingVsIndependent = (double)(independentTotal - aggregatedZstd) / independentTotal * 100.0;
            System.out.printf("独立 Zlib: %d, 聚合 ZSTD: %d, 节省: %.1f%% (聚合 Zlib: %d)%n",
                    independentTotal, aggregatedZstd, savingVsIndependent, aggregatedZlib);
        }
    }}
