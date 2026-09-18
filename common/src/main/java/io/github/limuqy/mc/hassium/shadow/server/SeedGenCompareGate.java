package io.github.limuqy.mc.hassium.shadow.server;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * 权威窗本地基线交付闸（方案 A 扩展）：除 OVD 外，任何本地基线
 * （SeedGen worldgen / 磁盘缓存 / 内存注入）在 compare 权威裁决
 * （UNCHANGED / DELTA / FULL）落地前，禁止交给真实客户端。
 * <p>
 * 标记写入：物化桥 seedGen/盘基线 defer、authority acquire 本地基线分支。
 * 标记清除：{@code ShadowPullClient.handleResponse} 成功落地前；
 * 网络权威 packet {@code injectChunk} 成功时；会话 reset / 断连整表清空。
 * OVD 不进本闸（无真服权威比对）。
 */
public final class SeedGenCompareGate {

    private static final Set<Long> AWAITING = ConcurrentHashMap.newKeySet();
    /** 本会话已获权威 compare 裁决（UNCHANGED/DELTA/FULL 成功落地）的柱。 */
    private static final Set<Long> CONFIRMED = ConcurrentHashMap.newKeySet();

    private SeedGenCompareGate() {}

    public static void mark(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        AWAITING.add(key);
        // 新的 awaiting 表示尚未拿到本柱权威裁决
        CONFIRMED.remove(key);
    }

    public static void clear(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        AWAITING.remove(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /** 权威 compare 成功落地：放行交付，并记住「已比对」供 drain 复用。 */
    public static void confirm(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        AWAITING.remove(key);
        CONFIRMED.add(key);
    }

    public static boolean isConfirmed(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        return CONFIRMED.contains(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static boolean isAwaiting(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        return AWAITING.contains(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static boolean isAwaiting(String dimension, int x, int z) {
        if (dimension == null) {
            return false;
        }
        return AWAITING.contains(DimensionKey.key(dimension, x, z));
    }

    /** 客户端交付统一闸：{@code true} = 禁止交付（仍等 compare）。 */
    public static boolean blockClientDelivery(String dimension, int x, int z) {
        return isAwaiting(dimension, x, z);
    }

    public static void clearAll() {
        AWAITING.clear();
        CONFIRMED.clear();
    }

    /** L0 诊断用。 */
    public static int size() {
        return AWAITING.size();
    }
}
