package io.github.limuqy.mc.hassium.client;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

/**
 * 客户端实体插值窗口自适应：服务端实体降帧（{@code master.entityTierIntervals} / 物品档 / 密度压力倍率）
 * 把出包间隔拉大到 3 tick 以上后，原版固定 3 tick 的插值窗口（{@code lerpTo(..., 3)}；
 * 1.21.5+ {@code InterpolationHandler.DEFAULT_INTERPOLATION_STEPS = 3}）会在每段走完后停顿等下一包，
 * 画面呈走-停顿挫。本类按同一实体<b>实际收包间隔</b>给出窗口步数：窗口 = 间隔时每段匀速首尾相接，
 * 轨迹与原版一样是连续折线。
 * <p>
 * 规则（{@link #planSteps}）：
 * <ul>
 *   <li>间隔 ∈ [1, {@value #MAX_CATCH_UP_GAP}] → 窗口 = 间隔（自适应段）；</li>
 *   <li>间隔 &gt; {@value #MAX_CATCH_UP_GAP}（首包，或静止期原版 60 tick 兜底重发之后）→ 回落原版
 *       {@value #VANILLA_STEPS}：上限必须小于原版静止重发周期，否则久静止实体会被学到超大窗口，
 *       突发移动时慢速滑行；</li>
 *   <li>间隔 ≤ 0（同一 tick 的第二个包，如位置包 + 头旋转包）→ 沿用上一窗口，不污染样本。</li>
 * </ul>
 * 窗口变大等价于显示滞后 ≈ 间隔/2——这是降帧本身的固有代价（信息每 N tick 才到一次，不平滑即顿挫），
 * 近档保持 3 时与原版行为一致；teleport 纠偏走 {@code handleTeleportEntity}，不经本类，保持原版快速对齐。
 * <p>
 * 状态仅客户端主线程访问（收包经 {@code ensureRunningOnSameThread} 串到主线程），
 * 登录时 {@link #clear()}。已知边缘：Display 实体移动时会短暂沿用自适应窗口
 * （其 data 插值时长由原版 {@code setInterpolationLength} 在数据同步时自行重设），影响仅低频命令移动。
 */
public final class EntityLerpPacing {

    /** 原版固定窗口步数。 */
    public static final int VANILLA_STEPS = 3;

    /** 自适应上限（tick）：必须小于原版静止 60 tick 兜底重发周期，见类注释。 */
    public static final int MAX_CATCH_UP_GAP = 20;

    private static final int LAST_SEEN_SHIFT = 8;
    private static final long STEPS_MASK = 0xFFL;

    /** entityId → (上次收包游戏 tick << 8) | 上次窗口步数；0 表示该实体未见过。 */
    private static final Int2LongOpenHashMap CADENCE = new Int2LongOpenHashMap();

    static {
        CADENCE.defaultReturnValue(0L);
    }

    private EntityLerpPacing() {
    }

    /**
     * 收包间隔 → 插值窗口步数（纯函数）。
     *
     * @param gap           距上次收包的 tick 数
     * @param previousSteps 上次窗口步数（gap ≤ 0 时沿用）
     * @return 本次窗口步数，恒 ≥ 1
     */
    public static int planSteps(long gap, int previousSteps) {
        if (gap <= 0) {
            return previousSteps > 0 ? previousSteps : VANILLA_STEPS;
        }
        if (gap > MAX_CATCH_UP_GAP) {
            return VANILLA_STEPS;
        }
        return (int) gap;
    }

    /**
     * 记录本次收包并返回该实体的插值窗口。位置包与头旋转包共用同一节奏表；
     * 每包恰调用一次（同 tick 第二包 gap = 0，幂等）。
     */
    public static int observeAndPlan(int entityId, long nowTick) {
        long packed = CADENCE.get(entityId);
        int steps = packed == 0L
                ? VANILLA_STEPS
                : planSteps(nowTick - (packed >>> LAST_SEEN_SHIFT), (int) (packed & STEPS_MASK));
        CADENCE.put(entityId, (nowTick << LAST_SEEN_SHIFT) | (steps & STEPS_MASK));
        return steps;
    }

    /** 登录时重置会话节奏表。 */
    public static void clear() {
        CADENCE.clear();
    }
}
