package io.github.limuqy.mc.hassium.shadow.storage;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ChunkStatusCompat;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;

/**
 * 影子列「是否携带可用方块内容」的判据 —— 内容 hash 与 compare 基线的准入条件。
 * <p>
 * <b>不变量</b>：影子存储里只有「有内容」的列可以携带内容 hash（{@code 0x48} + 8B 头），
 * 也只有「有内容」的列可以被读盘注入为 compare 基线柱。违反它会造成一条自激链：
 * <pre>
 *   半成品列（Status &lt; FEATURES）带 hash / 被注入
 *     → {@code ShadowStorageManager.probeLocalHash} 返回 OK，{@code injectedChunk != null}
 *     → {@code ShadowLightCompute.hasLocalPullBaseline} 变真
 *     → {@code ShadowTrackingSession.drainAuthorityAcquires} 把该柱踢出本地生成分支
 *       （本地生成门 = {@code isGenerationGateOpen() && !hasLocalPullBaseline}）
 *     → 只能发「空基线 compare」→ 服务端逐段 hash 全不匹配 → 整柱 FULL
 * </pre>
 * 实测（2026-09-21，1.21.1 fabric seedgen）：257 个 FULL 样本里 250 个的本地基线是
 * 全空气柱，来源即 {@code structure_starts}(600) / {@code biomes}(19) 这类半成品列。
 * <p>
 * 列本体<b>不删</b>：它仍是原版 worldgen 的续跑点（走 {@code RegionFile} 读路径），
 * 只是不再冒充「有内容」。
 */
public final class ShadowColumnContent {

    /** 列 NBT 顶层状态键（原版 {@code ChunkSerializer} 写入的 {@code Status}）。 */
    private static final String STATUS_KEY = "Status";

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    /** 扫描防护上限（真实列远小于此；超限视为非法 NBT）。 */
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ENTRIES = 4096;

    /** 扫描异常只报一次，避免异常列刷屏。 */
    private static final AtomicBoolean SCAN_ANOMALY_LOGGED = new AtomicBoolean();

    private ShadowColumnContent() {}

    /**
     * 已解析的列 NBT（读盘路径）：状态键直接可读，无扫描开销。
     *
     * @return true = 方块状态已定型，可作内容/基线
     */
    public static boolean isContentBearing(CompoundTag columnNbt) {
        return columnNbt != null && ChunkStatusCompat.isBlockFinal(statusOf(columnNbt));
    }

    /** {@code CompoundTag.getString} 跨版本读取：1.21.5 起返回 {@code Optional<String>}。 */
    private static String statusOf(CompoundTag columnNbt) {
#if MC_VER < MC_1_21_5
        return columnNbt.getString(STATUS_KEY);
#else
        return columnNbt.getString(STATUS_KEY).orElse("");
#endif
    }

    /**
     * 落盘前的有效内容 hash：半成品列一律不携带（返回 {@code null}）。
     * <p>
     * 写盘路径的唯一判据入口——{@code hash} 为 {@code null} 时原样返回，不做扫描。
     *
     * @param rawColumnNbt 未解析的列 NBT（写盘路径手上就有，只扫到 {@code Status} 即返回）
     * @param hash         坐标上记录的内容 hash（可能来自早先的权威注入，属陈旧值）
     * @return 可写入 sector 的 hash；半成品列返回 {@code null}
     */
    public static Long effectiveHash(byte[] rawColumnNbt, Long hash) {
        if (hash == null) {
            return null;
        }
        return isContentBearing(rawColumnNbt) ? hash : null;
    }

    /**
     * 未解析的列 NBT（写盘路径）：只扫到顶层 {@code Status} 键即返回
     * （原版把 {@code Status} 写在 {@code xPos/zPos/yPos} 之后，通常第 4 个键）。
     *
     * @return true = 方块状态已定型，可携带内容 hash
     */
    public static boolean isContentBearing(byte[] rawColumnNbt) {
        return ChunkStatusCompat.isBlockFinal(findRootString(rawColumnNbt, STATUS_KEY));
    }

    /** 顶层 CompoundTag 里取字符串键；非 compound 根 / 键缺失 / NBT 非法一律 null。 */
    private static String findRootString(byte[] data, String key) {
        if (data == null || data.length < 3) {
            return null;
        }
        try {
            Cursor cursor = new Cursor(data);
            if (cursor.u8() != TAG_COMPOUND) {
                return null;
            }
            // 原版 {@code NbtIo.writeUnnamedTag} 写的是 {@code 0A 00 00 <entries>}：
            // 根名长度为 0 的两字节**必须跳过**，否则第一个字节 0x00 会被当成 TAG_End，
            // 于是所有真实列都被判成「无内容」。
            cursor.utf();
            return cursor.rootString(key);
        } catch (NbtScanException e) {
            if (SCAN_ANOMALY_LOGGED.compareAndSet(false, true)) {
                Constants.LOG.warn("Hassium: shadow column NBT scan failed; treating column as "
                        + "contentless (further occurrences suppressed)");
            }
            return null;
        }
    }

    /** 只向前走的 NBT 游标：越界或非法标签抛 {@link NbtScanException}。 */
    private static final class Cursor {

        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int u8() {
            require(1);
            return data[pos++] & 0xFF;
        }

        int u16() {
            require(2);
            int value = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return value;
        }

        int i32() {
            require(4);
            int value = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return value;
        }

        String utf() {
            int length = u16();
            require(length);
            String value = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return value;
        }

        void skip(long bytes) {
            require(bytes);
            pos += (int) bytes;
        }

        /** 顶层 CompoundTag 逐条读，命中 key 且类型为 STRING 时返回其值。 */
        String rootString(String key) {
            for (int entries = 0; entries < MAX_ENTRIES; entries++) {
                int type = u8();
                if (type == TAG_END) {
                    return null;
                }
                String name = utf();
                if (key.equals(name)) {
                    return type == TAG_STRING ? utf() : null;
                }
                skipPayload(type, 0);
            }
            throw new NbtScanException();
        }

        void skipPayload(int type, int depth) {
            if (depth > MAX_DEPTH) {
                throw new NbtScanException();
            }
            switch (type) {
                case TAG_BYTE -> skip(1);
                case TAG_SHORT -> skip(2);
                case TAG_INT, TAG_FLOAT -> skip(4);
                case TAG_LONG, TAG_DOUBLE -> skip(8);
                case TAG_BYTE_ARRAY -> skip(i32());
                case TAG_STRING -> skip(u16());
                case TAG_LIST -> {
                    int elementType = u8();
                    int length = i32();
                    if (length < 0) {
                        throw new NbtScanException();
                    }
                    for (int i = 0; i < length; i++) {
                        skipPayload(elementType, depth + 1);
                    }
                }
                case TAG_COMPOUND -> {
                    for (int entries = 0; entries < MAX_ENTRIES; entries++) {
                        int inner = u8();
                        if (inner == TAG_END) {
                            return;
                        }
                        skip(u16());
                        skipPayload(inner, depth + 1);
                    }
                    throw new NbtScanException();
                }
                case TAG_INT_ARRAY -> skip(4L * i32());
                case TAG_LONG_ARRAY -> skip(8L * i32());
                default -> throw new NbtScanException();
            }
        }

        private void require(long bytes) {
            if (bytes < 0 || bytes > data.length - pos) {
                throw new NbtScanException();
            }
        }
    }

    private static final class NbtScanException extends RuntimeException {
        NbtScanException() {
            super(null, null, false, false);
        }
    }
}
