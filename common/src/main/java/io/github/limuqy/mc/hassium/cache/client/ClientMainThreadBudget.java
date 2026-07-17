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
    private static final int JOIN_BOOST_BUDGET_MS = 10;

    private static volatile long joinBoostUntilMs = 0L;

    private ClientMainThreadBudget() {
    }

    /**
     * 进服时启动 JoinBoost 窗口。
     */
    public static void startJoinBoost() {
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
     */
    public static long getBudgetNs() {
        int budgetMs;
        if (isJoinBoostActive()) {
            budgetMs = JOIN_BOOST_BUDGET_MS;
        } else {
            budgetMs = HassiumConfigService.getInstance().getMainThreadChunkBudgetMs();
        }
        return budgetMs * 1_000_000L;
    }

    /**
     * 本帧安全硬顶（最多 apply / 回调次数）。
     */
    public static int getHardCap() {
        return HassiumConfigService.getInstance().getMaxChunksPerFrame();
    }
}
