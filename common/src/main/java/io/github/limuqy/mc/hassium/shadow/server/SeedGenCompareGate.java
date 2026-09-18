package io.github.limuqy.mc.hassium.shadow.server;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * SeedGen 权威窗交付闸（方案 A）：本地 worldgen 首物化后先 compare，
 * 响应侧 UNCHANGED / DELTA / FULL 成功落地前，禁止任何路径把该柱交给真实客户端。
 * <p>
 * 标记在 {@code onChunkMaterialized} 的 defer 分支写入，在
 * {@code ShadowPullClient.handleResponse} 权威裁决落地前清除；
 * 会话 reset / 断连时整表清空。
 */
public final class SeedGenCompareGate {

    private static final Set<Long> AWAITING = ConcurrentHashMap.newKeySet();

    private SeedGenCompareGate() {}

    public static void mark(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        AWAITING.add(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static void clear(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        AWAITING.remove(DimensionKey.key(dimension, pos.x, pos.z));
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
    }

    /** L0 诊断用。 */
    public static int size() {
        return AWAITING.size();
    }
}
