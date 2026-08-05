package io.github.limuqy.mc.hassium.metrics;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * 全局管线压缩（globalPacketCompression）选型基准。
 * <p>
 * <b>对照基线</b>：实测冒烟 ROUND1 1.20.1 fabric 发现 {@code globalCompressionLevel=3→6}
 * 可让 actualRecv 9.6→7.4 MB（-23%）、带宽节省率 34.1%→46.7%、压缩比 1.52:1→1.88:1。
 * <p>
 * <b>本基准关键纠正</b>：
 * <ul>
 *   <li>MC 全局管线 threshold=256，{@code <256B} 包与原版一致 passthrough（VarInt(0)+raw），
 *       不参与压缩对比——故只测 ≥256B 真实进入压缩分支的包大小</li>
 *   <li>iteration/warmup 提到 100/20 抑制 ZSTD JIT 噪声，避免小样本下 lvl9 反比 lvl3 快的假象</li>
 *   <li>同时对比 {@link Zstd#compress}（无状态）与 {@link ZstdCompressCtx}（per-connection 复用，等价于生产管线
 *       {@code ZstdContextEncoder} 的复用模式），确认压缩率与速度是否与是否复用上下文相关</li>
 *   <li>配 Zlib lvl6（原版 MC 默认）对照——结论：256B~1KB 段 Zlib 6 反占优势，理由是 ZSTD 头部开销固定
 *       (~12B magic+frame)；1KB+ 段 ZSTD lvl6 显著反超</li>
 * </ul>
 * <p>
 * <b>不动 chunk 大包</b>：自定义 chunk channel 走 {@code compressionLevel=3}（Hassium 自家 ZSTD 通道），
 * 大量实测 chunk NBT 大包 lvl6 提升微小但速度减半，与本基准独立。
 */
class GlobalCompressionLevelBenchmarkTest {

    private static final int ZLIB_LEVEL = Deflater.DEFAULT_COMPRESSION; // 6
    private static final int WARMUP = 20;
    private static final int ITERATIONS = 100;

    /** 真实进入压缩分支的 size 桶（≥256B）；passthrough 段不参与对比 */
    private static final int[] WIRE_SIZES = {256, 512, 1024, 4096, 16384, 65536};
    /** 候选 ZSTD 全局级别：3（默认流畅档）/ 6（高压缩档）/ 9（高代价上限） */
    private static final int[] ZSTD_LEVELS = {3, 6, 9};

    /**
     * MC 结构化数据剖面：模拟全局管线面对的真实小/中/大包负载。
     * 混合 ~25% 短随机 + ~25% 零跑 + ~25% 低熵字节（VarInt/枚举/坐标）+ ~25% 模式重复。
     */
    private static byte[] mcData(int size, long seed) {
        byte[] d = new byte[size];
        Random r = new Random(seed);
        int i = 0;
        while (i < size) {
            int kind = r.nextInt(8);
            if (kind < 2) {
                int run = Math.min(r.nextInt(16) + 1, size - i);
                for (int j = 0; j < run; j++) d[i++] = (byte) r.nextInt(256);
            } else if (kind < 4) {
                int run = Math.min(r.nextInt(64) + 1, size - i);
                i += run;
            } else if (kind < 6) {
                byte val = (byte) r.nextInt(16);
                int run = Math.min(r.nextInt(32) + 1, size - i);
                for (int j = 0; j < run; j++) d[i++] = val;
            } else {
                int patLen = r.nextInt(4) + 3;
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

    /** Zlib 全帧压缩输出大小（含 MC 帧头 VarInt(uncompressedLen)） */
    private static int zlibWireBytes(byte[] raw) {
        Deflater d = new Deflater(ZLIB_LEVEL, false);
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[raw.length + 256];
        int n = d.deflate(buf);
        d.end();
        return varIntBytes(raw.length) + n;
    }

    /** ZSTD 无状态全帧（{@link Zstd#compress(byte[], int)}），含 MC 帧头 */
    private static int zstdWireBytes(byte[] raw, int level) {
        byte[] c = Zstd.compress(raw, level);
        return varIntBytes(raw.length) + c.length;
    }

    /** ZSTD 上下文压缩（per-connection 复用，等价 {@code ZstdContextEncoder} 生产路径） */
    private static int zstdCtxWireBytes(byte[] raw, int level, ZstdCompressCtx ctx) {
        byte[] c = ctx.compress(raw);
        return varIntBytes(raw.length) + c.length;
    }

    private static int varIntBytes(int v) {
        int n = 0;
        long x = Integer.toUnsignedLong(v);
        while ((x & ~0x7FL) != 0) { n++; x >>>= 7; }
        return n + 1;
    }

    private static double medianUs(Runnable task, int warmup, int iters) {
        for (int i = 0; i < warmup; i++) task.run();
        long[] times = new long[iters];
        for (int i = 0; i < iters; i++) {
            long start = System.nanoTime();
            task.run();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        return times[iters / 2] / 1_000.0;
    }

    @Test
    void benchmarkWireCompression() {
        // === 表 1：压缩率（wire / raw，%越低越省）===
        System.out.println("===== 全局管线压缩：压缩率 (wire/raw %) — 仅 ≥256B 真实压缩分支 =====");
        System.out.print("Size     \tZlib6\tZstd3\tZstd6\tZstd9\tZstdCtx3\tZstdCtx6\tZstdCtx9\t");
        System.out.println();

        for (int sz : WIRE_SIZES) {
            byte[] raw = mcData(sz, 42);
            double zlibRatio = (double) zlibWireBytes(raw) / sz * 100.0;
            System.out.printf(Locale.ROOT, "%-9d\t%.1f%%\t", sz, zlibRatio);
            for (int lv : ZSTD_LEVELS) {
                double zRatio = (double) zstdWireBytes(raw, lv) / sz * 100.0;
                System.out.printf(Locale.ROOT, "%.1f%%\t", zRatio);
            }
            // 用 ctx 测；为公平，每个 level 用独立 ctx（避免跨级别污染）
            for (int lv : ZSTD_LEVELS) {
                ZstdCompressCtx ctx = new ZstdCompressCtx();
                try {
                    ctx.setLevel(lv);
                    int wb = zstdCtxWireBytes(raw, lv, ctx);
                    System.out.printf(Locale.ROOT, "%.1f%%\t", (double) wb / sz * 100.0);
                } finally {
                    ctx.close();
                }
            }
            System.out.println();
        }

        // === 表 2：压缩速度（GB/s median of 100 iters）===
        System.out.println();
        System.out.println("===== 全局管线压缩：压缩速度 (GB/s median of " + ITERATIONS + " iters) =====");
        System.out.print("Size     \tZlib6\t");
        for (int lv : ZSTD_LEVELS) System.out.printf("Zstd%d\t", lv);
        for (int lv : ZSTD_LEVELS) System.out.printf("Ctx%d\t", lv);
        System.out.println();

        for (int sz : WIRE_SIZES) {
            byte[] raw = mcData(sz, 42);
            double rawGB = sz / (1024.0 * 1024.0 * 1024.0);

            double zlibSpd = rawGB / (medianUs(() -> {
                Deflater d = new Deflater(ZLIB_LEVEL, false);
                d.setInput(raw);
                d.finish();
                byte[] buf = new byte[sz + 256];
                d.deflate(buf);
                d.end();
            }, WARMUP, ITERATIONS) / 1_000_000.0);

            System.out.printf(Locale.ROOT, "%-9d\t%.2f\t", sz, zlibSpd);

            // 无状态 Zstd.compress
            for (int lv : ZSTD_LEVELS) {
                double zstdSpd = rawGB / (medianUs(() -> Zstd.compress(raw, lv), WARMUP, ITERATIONS) / 1_000_000.0);
                System.out.printf(Locale.ROOT, "%.2f\t", zstdSpd);
            }
            // 有状态 ZstdCompressCtx 复用（每个 size+level 复用同一 ctx，与生产路径一致）
            for (int lv : ZSTD_LEVELS) {
                ZstdCompressCtx ctx = new ZstdCompressCtx();
                try {
                    ctx.setLevel(lv);
                    double ctxSpd = rawGB / (medianUs(() -> ctx.compress(raw), WARMUP, ITERATIONS) / 1_000_000.0);
                    System.out.printf(Locale.ROOT, "%.2f\t", ctxSpd);
                } finally {
                    ctx.close();
                }
            }
            System.out.println();
        }

        // === 表 3：验证「passthrough 段（<256B）与原版行为一致」===
        System.out.println();
        System.out.println("===== 验证 <256B passthrough（与原版一致，不参与压缩）=====");
        System.out.println("Size     \tPassthru_wire/raw");
        for (int sz : new int[]{32, 64, 128, 200}) {
            byte[] raw = mcData(sz, 42);
            int pw = varIntBytes(0) + raw.length;
            System.out.printf(Locale.ROOT, "%-9d\t%.1f%%%n", sz, (double) pw / sz * 100.0);
        }
    }
}
