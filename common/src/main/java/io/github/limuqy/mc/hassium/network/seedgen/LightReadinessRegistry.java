package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.utils.DimensionKey;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 影子光照就绪注册表（E1「事件做骨」）：列 pos（{@link DimensionKey} 复合键）→
 * {@code INGESTED → LIT → SURROUNDED} 状态机。
 *
 * <p>背景（work/LightDiag-TASK.md）：首跑 {@code lightChunk} 在邻柱未齐时超时放行，
 * 引擎把错误定值写入层存储且不自访（光照是固定点问题）——三轮「查询式」修复死于
 * 邻域查询判不出（缓存命中柱跳过光屏障不入 {@code initializedLight} / 角落柱不注入 /
 * 全局静默点流式加载下不可达）。本注册表改为<b>事件驱动</b>：
 *
 * <ul>
 *   <li><b>INGESTED</b>：方块数据已注入（{@code injectChunk} / {@code injectLoadedChunk}
 *       全部入口，含缓存命中直载与直推）；REPLACE 重注入会清光，状态回退 INGESTED。</li>
 *   <li><b>LIT</b>：本柱光照已在引擎可查——唯一事件源是引擎 {@code lightChunk} 完成
 *       （覆盖 PENDING/GENERATED/DELTA/LIGHT_ONLY 全部屏障来源；缓存复用
 *       {@code REUSE_CACHE} 也走屏障，只是值来自存档、记为「未在会话内计算」）。</li>
 *   <li><b>SURROUNDED</b>：自身 LIT 且 8 邻全 LIT。触发整柱 LIGHT_ONLY 清层重算的
 *       证据线（方案 D，LightFinal 根因修复）：存在「晚于本柱末次会话内计算」达成
 *       converged=true 终值的邻柱，或本柱光来自存档复用（lastCompute=0）。重算完成才
 *       出队；已 SURROUNDED 的列在邻列 settled 证据到达后仍参与重评。</li>
 * </ul>
 *
 * <p>时间戳不变量（L0 单测锁死）：{@code litAtMs} 只在 INGESTED→LIT 跃迁时刷新；
 * {@code settledAtMs} 只在 converged=true 完成时刷新且单调不回退——它是唯一传播源。
 * 终止性：触发源 = 「某柱新达成 true」这一单调事件；true 柱的 8 邻已全部完成过计算，
 * 自身不再被 relight 清层 → 证据不回退、有向无环；每柱对每个邻柱的 true 达成至多响应
 * 一次，总重算上界 ~9N。（对比：若以原始完成时刻为判据，A/B 交替重算互相顶高对方
 * 时间戳 → 无限乒乓，已被证伪。）
 *
 * <p>纯 Java、无 Minecraft 依赖；时间戳由调用方注入以便单测确定序。线程安全：
 * 事件来自引擎 ForkJoinPool / 消费循环 / 主线程多面，per-column 锁只覆盖单列字段
 * 变更，绝不同时持两列锁（邻列读取走 volatile 字段）。
 */
final class LightReadinessRegistry {

    private LightReadinessRegistry() {}

    /** 列状态。 */
    enum Phase {
        /** 方块数据已注入，光未就绪。 */
        INGESTED,
        /** 本柱光照引擎可查（lightChunk 已完成，值可能暂态欠收敛）。 */
        LIT,
        /** 自身 LIT 且 8 邻全 LIT：跨柱蔓延输入齐备，可判定/修复终值。 */
        SURROUNDED
    }

    /** 收敛重算触发回调：ShadowLightCompute 注册，实现整柱 LIGHT_ONLY 入队 + pump。 */
    interface RelightTrigger {
        void trigger(long key);
    }

    /** 每列簿记。lock 只保护单列字段变更；时间戳/相位另以 volatile 保证跨锁可见读。 */
    static final class Column {
        final Object lock = new Object();
        volatile Phase phase = Phase.INGESTED;
        /** 最近一次 INGESTED→LIT 跃迁时刻（ms）；0 = 未 LIT。同柱重算不刷新。 */
        volatile long litAtMs;
        /** 末次「会话内真实计算」（lightChunk 非 REUSE_CACHE 完成）时刻；0 = 光来自存档复用/未知。 */
        volatile long lastComputeAtMs;
        /**
         * 邻列可见的「终值证据」：本柱最近一次以 converged=true 完成整柱计算的时刻；
         * 0 = 尚无终值。方案 D 唯一传播源——见类 javadoc 终止性论证。
         */
        volatile long settledAtMs;
        /** 收敛重算已入队未完成（重算完成才出队；防重复入队）。 */
        volatile boolean pendingConverge;
    }

    private static final ConcurrentHashMap<Long, Column> columns = new ConcurrentHashMap<>();

    private static volatile RelightTrigger relightTrigger;

    /** 结构变更计数（诊断/测试观察用）。 */
    private static final AtomicLong eventCount = new AtomicLong();

    /** 8 邻偏移（切比雪夫半径 1，不含自身）。 */
    private static final int[][] NEIGHBORS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1}, {0, 1},
            {1, -1}, {1, 0}, {1, 1}
    };

    /** 注册收敛重算触发回调（ShadowLightCompute 静态初始化时调用；null = 摘除）。 */
    static void setRelightTrigger(RelightTrigger trigger) {
        relightTrigger = trigger;
    }

    /**
     * 方块数据注入事件（INGESTED）。全部注入入口必须调用：
     * REPLACE 重注入清光 → 已 LIT/SURROUNDED 的列回退 INGESTED 并清时间戳，
     * 防旧光事件语义泄漏到新数据。幂等。
     */
    static void markIngested(long key) {
        Column col = columns.computeIfAbsent(key, k -> new Column());
        synchronized (col.lock) {
            if (col.phase != Phase.INGESTED) {
                col.phase = Phase.INGESTED;
                col.litAtMs = 0L;
                col.lastComputeAtMs = 0L;
                col.settledAtMs = 0L;
                col.pendingConverge = false;
                eventCount.incrementAndGet();
            }
        }
    }

    /**
     * 引擎 {@code lightChunk} 完成事件（LIT 唯一来源）。
     *
     * @param reusedFromStorage true = REUSE_CACHE（值来自存档/引擎既有层，非会话内计算）；
     *                          false = 真实计算，刷新 lastComputeAt 并使待收敛出队
     * @param converged         本柱完成时 8 邻是否齐备无并发（调用方 whenComplete 判定）；
     *                          true = 终值证据（settledAtMs，方案 D 唯一传播源）
     * @param nowMs             事件时刻（生产传 {@code System.currentTimeMillis()}）
     */
    static void onLightComputed(long key, boolean reusedFromStorage, boolean converged, long nowMs) {
        Column col = columns.computeIfAbsent(key, k -> new Column());
        List<Long> recheck = new ArrayList<>(9);
        synchronized (col.lock) {
            if (!reusedFromStorage) {
                if (nowMs > col.lastComputeAtMs) {
                    col.lastComputeAtMs = nowMs;
                }
                // 重算完成才出队（LIGHT_ONLY 收敛重算同样走本事件）
                boolean wasPending = col.pendingConverge;
                col.pendingConverge = false;
                if (converged) {
                    // 方案 D：converged=true 完成 = 终值证据（唯一传播源）。
                    // false 完成不记录——其值可能仍基于邻列旧层，不构成对邻居的修正依据。
                    if (nowMs > col.settledAtMs) {
                        col.settledAtMs = nowMs;
                    }
                    eventCount.incrementAndGet();
                } else if (wasPending) {
                    // 待收敛重算以 false 落地：重新武装，等待邻列 true 证据再评
                    eventCount.incrementAndGet();
                }
            }
            if (col.phase == Phase.INGESTED) {
                // 跃迁时刻只在 INGESTED→LIT 记一次；同柱重算不刷新。
                // 注意：park 柱迟到的 lightChunk 完成不构成新跃迁——它的值修正
                // 由 converged=true 的 settledAtMs 证据传播（LightFinal 根因修复点）。
                col.litAtMs = Math.max(nowMs, col.litAtMs);
                col.phase = Phase.LIT;
                eventCount.incrementAndGet();
            }
        }
        // 自身 + 8 邻都重评：SURROUNDED 升级（跃迁波，首轮铺开）
        // 与 settled 证据波（方案 D，滞后修正）共用该扫描面。
        recheck.add(key);
        int cx = DimensionKey.chunkXOf(key);
        int cz = DimensionKey.chunkZOf(key);
        String dimension = DimensionKey.dimensionOf(key);
        for (int[] d : NEIGHBORS) {
            long nKey = DimensionKey.key(dimension, cx + d[0], cz + d[1]);
            if (columns.containsKey(nKey)) {
                recheck.add(nKey);
            }
        }
        for (Long k : recheck) {
            reevaluate(k);
        }
    }

    /**
     * 单列收敛评估：LIT → 尝试升级 SURROUNDED（跃迁波）；SURROUNDED → 重评触发条件
     * （settled 证据波）。幂等、无锁嵌套（per-column 锁逐个获取）。
     */
    private static void reevaluate(long key) {
        Column col = columns.get(key);
        if (col == null || col.litAtMs == 0L) {
            return;
        }
        if (col.phase == Phase.LIT) {
            if (newestNeighborLitAt(key) == 0L) {
                return; // 尚有邻柱未 LIT
            }
            boolean promoted = false;
            synchronized (col.lock) {
                if (col.phase == Phase.LIT) {
                    col.phase = Phase.SURROUNDED;
                    promoted = true;
                    eventCount.incrementAndGet();
                }
            }
            if (!promoted && col.phase != Phase.SURROUNDED) {
                return; // 并发回退 INGESTED 等竞态：放弃本次
            }
        }
        if (col.phase != Phase.SURROUNDED) {
            return;
        }
        maybeTriggerRelight(key, col);
    }

    /** 8 邻最大跃迁 litAt（最晚变 LIT 的邻柱时刻）；任一邻柱未 LIT 返回 0。 */
    private static long newestNeighborLitAt(long key) {
        String dimension = DimensionKey.dimensionOf(key);
        int cx = DimensionKey.chunkXOf(key);
        int cz = DimensionKey.chunkZOf(key);
        long max = 0L;
        for (int[] d : NEIGHBORS) {
            long nKey = DimensionKey.key(dimension, cx + d[0], cz + d[1]);
            Column n = columns.get(nKey);
            if (n == null || n.litAtMs == 0L) {
                return 0L;
            }
            if (n.litAtMs > max) {
                max = n.litAtMs;
            }
        }
        return max;
    }

    /** 8 邻最大 settledAt（最晚达成 converged=true 终值的邻柱时刻）；无证据返回 0。 */
    private static long newestNeighborSettledAt(long key) {
        String dimension = DimensionKey.dimensionOf(key);
        int cx = DimensionKey.chunkXOf(key);
        int cz = DimensionKey.chunkZOf(key);
        long max = 0L;
        for (int[] d : NEIGHBORS) {
            long nKey = DimensionKey.key(dimension, cx + d[0], cz + d[1]);
            Column n = columns.get(nKey);
            if (n == null) {
                continue; // 未注册邻柱无证据，不阻断（与跃迁波「未 LIT 阻断」不同）
            }
            if (n.settledAtMs > max) {
                max = n.settledAtMs;
            }
        }
        return max;
    }

    /**
     * 收敛判定（方案 D）：存在「晚于本柱末次会话内计算」达成 converged=true 终值的
     * 邻柱 → 我的边界可能基于该邻的旧值 → 触发整柱重算。
     * <p>两条独立触发线：
     * <ul>
     *   <li><b>settled 证据线</b>：newestNeighborSettledAt &gt; lastComputeAt——
     *       park 柱迟到 lightChunk 完成(true)即走此线修正 LightFinal 根因；</li>
     *   <li><b>存档复用线</b>：lastComputeAt==0 恒触发一次性校验重算（错误定值不得
     *       随存档复活）。</li>
     * </ul>
     * pendingConverge 去重：重算完成才允许再次触发。终止性见类 javadoc：
     * true 达成单调不回退，每柱对每邻至多响应一次，上界 ~9N。
     */
    private static void maybeTriggerRelight(long key, Column col) {
        synchronized (col.lock) {
            if (col.pendingConverge || col.phase != Phase.SURROUNDED) {
                return;
            }
            boolean needed = col.lastComputeAtMs == 0L
                    || newestNeighborSettledAt(key) > col.lastComputeAtMs;
            if (!needed) {
                return;
            }
            col.pendingConverge = true;
            eventCount.incrementAndGet();
        }
        RelightTrigger trigger = relightTrigger;
        if (trigger != null) {
            try {
                trigger.trigger(key);
            } catch (Throwable ignored) {
                // 触发面异常不反噬状态机；pendingConverge 由 abandonConverge / 重算完成回收
            }
        }
    }

    /**
     * 放弃待收敛登记：目标柱不可重算（未注入 / 维度未装配）时由触发方回调，
     * 允许后续事件重新评估，防 pendingConverge 永久卡死泄漏。
     */
    static void abandonConverge(long key) {
        Column col = columns.get(key);
        if (col == null) {
            return;
        }
        synchronized (col.lock) {
            col.pendingConverge = false;
        }
    }

    /**
     * 存档终态门：仅「SURROUNDED 且无待收敛重算」的列允许把 {@code isLightCorrect=true}
     * 落盘（写 isLightOn + 光层 NBT）。其余列落盘省略光 → 下次读盘强制续算，
     * 堵死「错误定值随 type 126 存档复活」。注册表缺失（未接线/已清理）按 false 保守处理。
     */
    /**
     * 8 邻是否全部处于「已 LIT 且无待收敛重算」的稳定态（方案 D：LIGHT_ONLY 收敛
     * 重算完成时的 converged 判定依据）。park 未 LIT（INGESTED）邻柱按未就绪计，
     * 与官方 ChunkPyramid LIGHT 对 INITIALIZE_LIGHT 的 radius=1 硬依赖语义对齐。
     * 本柱自身状态不参与判定。
     */
    static boolean areNeighborsSettled(long key) {
        String dimension = DimensionKey.dimensionOf(key);
        int cx = DimensionKey.chunkXOf(key);
        int cz = DimensionKey.chunkZOf(key);
        for (int[] d : NEIGHBORS) {
            long nKey = DimensionKey.key(dimension, cx + d[0], cz + d[1]);
            Column n = columns.get(nKey);
            if (n == null || n.phase == Phase.INGESTED || n.pendingConverge) {
                return false;
            }
        }
        return true;
    }

    static boolean isSettled(long key) {
        Column col = columns.get(key);
        if (col == null) {
            return false;
        }
        return col.phase == Phase.SURROUNDED && !col.pendingConverge;
    }

    /** 柱卸载/驱逐：移除簿记（下次再入重建）。 */
    static void remove(long key) {
        columns.remove(key);
    }

    /** 断连/降级清理：对齐 ShadowLightCompute.onDisconnect 清理面。幂等。 */
    static void clear() {
        columns.clear();
        eventCount.set(0L);
    }

    // ---- 诊断 / 测试观察面 ----

    static Phase phaseOf(long key) {
        Column col = columns.get(key);
        return col == null ? null : col.phase;
    }

    static long litAtOf(long key) {
        Column col = columns.get(key);
        return col == null ? 0L : col.litAtMs;
    }

    static long lastComputeAtOf(long key) {
        Column col = columns.get(key);
        return col == null ? 0L : col.lastComputeAtMs;
    }

    static long settledAtOf(long key) {
        Column col = columns.get(key);
        return col == null ? 0L : col.settledAtMs;
    }

    static boolean isPendingConverge(long key) {
        Column col = columns.get(key);
        return col != null && col.pendingConverge;
    }

    static int size() {
        return columns.size();
    }

    static long eventCount() {
        return eventCount.get();
    }
}
