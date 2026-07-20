package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;

/**
 * 客户端主线程区块应用时间预算。
 * <p>
 * 用本帧已消耗的 {@code nanoTime} 约束 apply / 回调吞吐，替代滞后的 FPS 自适应。
 * 进服后短时 JoinBoost 提高预算，摊平「停顿后突进」。
 */
public final class ClientMainThreadBudget {

    /** 进服加速窗口时长（毫秒） */
    private static final long JOIN_BOOST_DURATION_MS = 5_000;

    /** JoinBoost 期间的预算（毫秒） */
    private static final int JOIN_BOOST_BUDGET_MS = 30;

    private static volatile long joinBoostUntilMs = 0L;

    private ClientMainThreadBudget() {
    }

    /**
     * 进服时启动 JoinBoost 窗口。
     * <p>
     * 若配置 {@code clientCache.joinBoostEnabled=false} 则不启动（预算始终为 normalBudgetMs，
     * OVD_LOAD_THRESHOLD 限流始终生效）。
     */
    public static void startJoinBoost() {
        if (!HassiumConfigService.getInstance().isJoinBoostEnabled()) {
            return;
        }
        joinBoostUntilMs = System.currentTimeMillis() + JOIN_BOOST_DURATION_MS;
    }

    /**
     * 断连时清除 JoinBoost。
     */
    public static void clearJoinBoost() {
        joinBoostUntilMs = 0L;
    }

    /**
     * 当前是否处于 JoinBoost 窗口。
     */
    public static boolean isJoinBoostActive() {
        long until = joinBoostUntilMs;
        return until > 0L && System.currentTimeMillis() < until;
    }

    /**
     * 本帧可用的时间预算（纳秒）。
     * <p>
     * JoinBoost 窗口内线性退坡：boostBudgetMs → normalBudgetMs（over JOIN_BOOST_DURATION_MS），
     * 避免窗口结束时预算突降导致加载节奏断崖（快-慢-快波动）。
     */
    public static long getBudgetNs() {
        int normalBudgetMs = HassiumConfigService.getInstance().getMainThreadChunkBudgetMs();
        long now = System.currentTimeMillis();
        long until = joinBoostUntilMs;
        if (until > 0 && now < until) {
            // JoinBoost 线性退坡：boostBudgetMs → normalBudgetMs
            // boostBudgetMs 至少不低于 normalBudgetMs（用户调高 normalBudgetMs 时 JoinBoost 不反向降预算）
            int boostBudgetMs = Math.max(JOIN_BOOST_BUDGET_MS, normalBudgetMs);
            long remaining = until - now;
            double ratio = (double) remaining / JOIN_BOOST_DURATION_MS; // 1.0 → 0.0（窗口内递减）
            int budgetMs = (int) Math.round(normalBudgetMs + (boostBudgetMs - normalBudgetMs) * ratio);
            return budgetMs * 1_000_000L;
        }
        return normalBudgetMs * 1_000_000L;
    }

    /**
     * 本帧安全硬顶（最多 apply / 回调次数）。
     */
    public static int getHardCap() {
        return HassiumConfigService.getInstance().getMaxChunksPerFrame();
    }
}
