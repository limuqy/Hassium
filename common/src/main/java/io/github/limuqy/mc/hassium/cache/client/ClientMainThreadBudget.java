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
    private static final long JOIN_BOOST_DURATION_MS = 10_000;

    /** 窗口内区块 apply 活跃时的续期时长（毫秒）：全量加载 >10s 时预算不中途退坡 */
    private static final long RENEW_WINDOW_MS = 5_000;

    /** JoinBoost 期间的预算（毫秒） */
    private static final int JOIN_BOOST_BUDGET_MS = 30;

    private static volatile long joinBoostUntilMs = 0L;

    /** 最近一次权威区块 apply 的时间戳（settle 写回判定：加载风暴停止的安静窗口）。 */
    private static volatile long lastApplyNano = 0L;

    /**
     * 本帧已消耗的缓存区块 apply 名额。
     * <p>
     * 覆盖全部缓存读取路径：readyQueue 消费（权威 + OVD renderOnly）与 OVD unload
     * substitute 即时替换，共用 {@code chunk.maxChunksPerFrame} 硬顶——保证
     * 超视距/替换路径不会绕过帧限速挤占主线程。
     */
    private static int frameCacheApplies = 0;

    private static int frameCacheHardCap = 12;

    private ClientMainThreadBudget() {
    }

    /**
     * 每帧开始重置缓存 apply 配额（Minecraft.tick HEAD，渲染帧粒度）。
     */
    public static void beginFrameCacheBudget() {
        frameCacheApplies = 0;
        frameCacheHardCap = HassiumConfigService.getInstance().getMaxChunksPerFrame();
    }

    /**
     * 尝试获取一个本帧缓存 apply 名额（任何缓存读取路径统一入口）。
     *
     * @return true=获得名额（本帧还可继续 apply）；false=本帧配额已满
     */
    public static boolean tryAcquireCacheApply() {
        if (frameCacheApplies >= frameCacheHardCap) {
            return false;
        }
        frameCacheApplies++;
        return true;
    }

    /**
     * 进服时启动 JoinBoost 窗口。
     * <p>
     * 若配置 {@code chunk.joinBoostEnabled=false} 则不启动（预算始终为 normalBudgetMs，
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
     * 区块 apply 活跃时续期 JoinBoost 窗口。
     * <p>
     * 进服全量加载（1021 块 × ~11ms）超过固定 10s 窗口时，后段预算会线性退坡到 normal
     * → 实测 16s 后加载速率从 70/s 掉到 33/s。加载洪峰期间每次 apply 续期 5s，
     * 让高预算持续到加载完成；窗口过期后不再续期（移动/飞行环带不会永久占用高预算）。
     * 预算只是上限：零星 apply 用不满，不会凭空占用帧时间。
     */
    public static void noteChunkApplyActivity() {
        lastApplyNano = System.nanoTime();
        long until = joinBoostUntilMs;
        if (until > 0L && System.currentTimeMillis() < until) {
            joinBoostUntilMs = System.currentTimeMillis() + RENEW_WINDOW_MS;
        }
    }

    /**
     * 最近一次权威区块 apply 的时间（纳秒）；从未 apply 返回 0。
     */
    public static long getLastApplyNano() {
        return lastApplyNano;
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
     * JoinBoost 窗口内恒用高预算（不线性退坡）：
     * 续期机制（{@link #noteChunkApplyActivity}）保证窗口只在加载空闲后过期，
     * 窗口内退坡反而会把续期后的预算砍到中间值（remaining/10s 比例）→
     * 实测 1021 块全量加载后段速率从 70/s 掉到 33/s。
     * 窗口到期瞬间的降档只发生在加载已结束/空闲时，无感知。
     */
    public static long getBudgetNs() {
        int normalBudgetMs = HassiumConfigService.getInstance().getMainThreadChunkBudgetMs();
        long now = System.currentTimeMillis();
        long until = joinBoostUntilMs;
        if (until > 0 && now < until) {
            // boostBudgetMs 至少不低于 normalBudgetMs（用户调高 normalBudgetMs 时 JoinBoost 不反向降预算）
            int boostBudgetMs = Math.max(JOIN_BOOST_BUDGET_MS, normalBudgetMs);
            return boostBudgetMs * 1_000_000L;
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
