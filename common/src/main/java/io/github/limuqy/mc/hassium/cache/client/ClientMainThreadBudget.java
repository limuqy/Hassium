package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端主线程区块应用时间预算，以及影子端缓存读取生产配额。
 * <p>
 * 消费（apply / 回调 / 影子回传落地）只用本帧 {@code nanoTime} 预算；
 * {@code chunk.maxChunksPerFrame} 只约束缓存读取生产（OVD 入队、影子读盘），
 * 服务端推送有自己的限流，客户端不重复掐。
 * 进服后短时 JoinBoost 提高时间预算与读盘配额，摊平「停顿后突进」。
 * JoinBoost 自 {@link #startJoinBoost()} 起 30s 宽松封顶：apply 活跃可续期，但总窗口不超 30s。
 */
public final class ClientMainThreadBudget {

    /** 进服加速窗口时长（毫秒） */
    private static final long JOIN_BOOST_DURATION_MS = 10_000;

    /** 窗口内区块 apply 活跃时的续期时长（毫秒）：全量加载 >10s 时预算不中途退坡 */
    private static final long RENEW_WINDOW_MS = 5_000;

    /** JoinBoost 总时长宽松封顶（毫秒）：自 startJoinBoost 起算，续期不越此上限 */
    private static final long JOIN_BOOST_CAP_MS = 30_000;

    /** JoinBoost 期间的预算（毫秒） */
    private static final int JOIN_BOOST_BUDGET_MS = 30;

    /**
     * JoinBoost 期间每 tick 缓存读取生产配额下限。默认 {@code chunk.maxChunksPerFrame}=6
     * 在 20 tick/s 下理论 120/s，但实测进服帧时常掉到 ~8–10Hz，6×10=60 仍低于 15s 铺满所需 ~67/s。
     */
    private static final int JOIN_BOOST_HARD_CAP = 12;

    private static volatile long joinBoostUntilMs = 0L;

    /** JoinBoost 封顶截止（毫秒）：startJoinBoost 记 now+30s；续期与读取均不越过此值。 */
    private static volatile long joinBoostDeadlineMs = 0L;

    /** 最近一次权威区块 apply 的时间戳（settle 写回判定：加载风暴停止的安静窗口）。 */
    private static volatile long lastApplyNano = 0L;

    /** 本 tick 剩余缓存读取次数（OVD 入队 + 影子 {@code loadFromDisk}）。 */
    private static final AtomicInteger cacheReadsRemaining = new AtomicInteger();

    private ClientMainThreadBudget() {
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
        long now = System.currentTimeMillis();
        joinBoostDeadlineMs = now + JOIN_BOOST_CAP_MS;
        joinBoostUntilMs = now + JOIN_BOOST_DURATION_MS;
    }

    /**
     * 断连时清除 JoinBoost。
     */
    public static void clearJoinBoost() {
        joinBoostUntilMs = 0L;
        joinBoostDeadlineMs = 0L;
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
            joinBoostUntilMs = Math.min(System.currentTimeMillis() + RENEW_WINDOW_MS, joinBoostDeadlineMs);
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
        long until = Math.min(joinBoostUntilMs, joinBoostDeadlineMs);
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
     * <p>
     * JoinBoost 期间 {@link #dispatcherShareNs(long)} 只把一半分给 dispatcher，
     * 另一半预留给影子 {@code drainReady}，避免帧 deadline 在落地前已过期。
     */
    public static long getBudgetNs() {
        int normalBudgetMs = HassiumConfigService.getInstance().getMainThreadChunkBudgetMs();
        long now = System.currentTimeMillis();
        long until = Math.min(joinBoostUntilMs, joinBoostDeadlineMs);
        if (until > 0 && now < until) {
            // boostBudgetMs 至少不低于 normalBudgetMs（用户调高 normalBudgetMs 时 JoinBoost 不反向降预算）
            int boostBudgetMs = Math.max(JOIN_BOOST_BUDGET_MS, normalBudgetMs);
            return boostBudgetMs * 1_000_000L;
        }
        return normalBudgetMs * 1_000_000L;
    }

    /**
     * 本帧 dispatcher 可用的预算份额（纳秒）。JoinBoost 预留一半给 {@code drainReady}；
     * 非 JoinBoost 为满额（drainReady 吃 dispatcher 剩余）。
     */
    public static long dispatcherShareNs(long budgetNs) {
        return dispatcherShareNs(budgetNs, isJoinBoostActive());
    }

    /** 测试缝：JoinBoost 时 dispatcher 与 drainReady 对半分账。 */
    static long dispatcherShareNs(long budgetNs, boolean joinBoost) {
        if (budgetNs <= 0L) {
            return 0L;
        }
        return joinBoost ? budgetNs / 2L : budgetNs;
    }

    /**
     * 本 tick 缓存读取生产配额（{@code chunk.maxChunksPerFrame}；JoinBoost 期间下限 12）。
     * 不用于主线程消费。
     */
    public static int getHardCap() {
        int base = Math.max(1, HassiumConfigService.getInstance().getMaxChunksPerFrame());
        if (isJoinBoostActive()) {
            return Math.max(base, JOIN_BOOST_HARD_CAP);
        }
        return base;
    }

    /** 客户端 tick 开头重置本 tick 的缓存读取配额。 */
    public static void resetCacheReadBudget() {
        cacheReadsRemaining.set(getHardCap());
    }

    /**
     * 占用一次缓存读取（OVD 入队或影子读盘）。配额用尽返回 {@code false}，调用方应把剩余工作留到下 tick。
     */
    public static boolean tryAcquireCacheRead() {
        while (true) {
            int n = cacheReadsRemaining.get();
            if (n <= 0) {
                return false;
            }
            if (cacheReadsRemaining.compareAndSet(n, n - 1)) {
                return true;
            }
        }
    }

    /** {@link #tryAcquireCacheRead()} 之后实际未发起读取时退还配额。 */
    public static void refundCacheRead() {
        cacheReadsRemaining.incrementAndGet();
    }
}
