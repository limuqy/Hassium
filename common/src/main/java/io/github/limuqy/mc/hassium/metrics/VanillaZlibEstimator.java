package io.github.limuqy.mc.hassium.metrics;

import java.util.zip.Deflater;

/**
 * 模拟原版 Zlib 管线（CompressionEncoder）的单帧压缩估算。
 *
 * <p>参数复刻 MC 默认：Deflater.DEFAULT_COMPRESSION (6), threshold=256, nowrap=false。
 * 线协议：低于阈值 → VarInt(0) + 原始负载；否则 → VarInt(uncompressedLength) + 压缩负载。
 *
 * <p>两种重载：
 * <ul>
 *   <li>{@link #estimate(byte[])} — 精确 Deflater 压缩（有实际字节时）</li>
 *   <li>{@link #estimate(int)} — 大小近似（仅有原始 size 时，基于 MC 区块 NBT 典型压缩率）</li>
 * </ul>
 *
 * <p>性能：仅 metrics 开启时调用；默认关闭零开销。
 */
public final class VanillaZlibEstimator {

    private static final int MC_THRESHOLD = 256;
    private static final int ZLIB_LEVEL = Deflater.DEFAULT_COMPRESSION;

    private VanillaZlibEstimator() {}

    /**
     * 精确估算：对实际负载字节运行 Deflater 得到 Zlib 帧大小。
     *
     * @param rawPayload 未压缩负载字节，长度 > 0
     * @return Zlib 压缩帧字节数（含 VarInt 头）；metrics 关闭时返回 0
     */
    public static int estimate(byte[] rawPayload) {
        if (!NetworkStats.isEnabled()) return 0;
        int len = rawPayload.length;
        if (len < MC_THRESHOLD) {
            return varIntBytes(0) + len;
        }
        Deflater def = new Deflater(ZLIB_LEVEL, false);
        try {
            def.setInput(rawPayload);
            def.finish();
            byte[] buf = new byte[len + 64];
            // review-fix: T9-36 循环 deflate 至 finished() 并按 getTotalOut() 计——
            // 极端不可压缩输入（stored-block 开销）下 len+64 缓冲可能不足，单次 deflate 截断会低估 Zlib 帧
            while (!def.finished()) {
                def.deflate(buf);
            }
            return varIntBytes(len) + (int) def.getTotalOut();
        } finally {
            def.end();
        }
    }

    /**
     * 粗估 Zlib 帧大小（仅有原始 size 数字时）。
     * 公式基于真实 MC 区块 NBT 数据剖面（典型 Zlib 压缩率 25-40%），略保守以不夸大 ZSTD 优势：
     * <ul>
     *   <li>&lt;256 → VarInt(0) + raw（不压缩）</li>
     *   <li>256-4095 → ~80% of raw（小负载保守估计：不可压缩输入 zlib stored-block 帧 ≈ raw+11B，
     *       压缩收益有限；0.35 对真实小输入过于乐观）</li>
     * </ul>
     *
     * @param rawSize 未压缩负载字节数
     * @return 估算的 Zlib 帧字节数（含 VarInt 头）；metrics 关闭时返回 0
     */
    public static int estimate(int rawSize) {
        if (!NetworkStats.isEnabled()) return 0;
        if (rawSize < MC_THRESHOLD) {
            return varIntBytes(0) + rawSize;
        }
        double ratio;
        // review-fix: T9-37 小段（256-4095）改用保守比率 0.8——原 0.35 对不可压缩小输入过度乐观，
        // 且 256B 处帧大小骤降 2/3（255→256B 的阈值突变源于 MC 协议本身：低于阈值不压缩）
        if (rawSize <= 4095) {
            ratio = 0.8;
        } else if (rawSize <= 65535) {
            ratio = 0.30;
        } else {
            ratio = 0.25;
        }
        return (int) (rawSize * ratio) + varIntBytes(rawSize);
    }

    private static int varIntBytes(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) { n++; v >>>= 7; }
        return n;
    }
}