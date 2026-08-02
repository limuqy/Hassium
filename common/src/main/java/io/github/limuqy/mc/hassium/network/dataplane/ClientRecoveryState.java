package io.github.limuqy.mc.hassium.network.dataplane;

/**
 * Task 7 — 客户端自动重连恢复的状态机（单例）。
 *
 * <p>phase 转换是单边的：
 * <pre>
 *   NONE --begin--> RECOVERING --markRecovered--> RECOVERED --markTerminal--> TERMINAL
 *   NONE --begin--> RECOVERING ------------------markTerminal-----------------> TERMINAL
 *   NONE ---------------------------------------markTerminal-----------------> TERMINAL
 * </pre>
 *
 * <p>{@link #shouldSuppressFinalization()} / {@link #isRecovering()} 在 RECOVERING 与
 * RECOVERED 阶段返回 true，期间不允许
 * {@link io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper#finalizeDisconnect()}
 * 关闭磁盘缓存 / executor / save 线程 —— 这些资源要继续承载恢复后的会话。一旦 markTerminal，
 * finalize 就由 {@link #consumeTerminalCleanup()} 释放一次，保证正常 logout 与恢复耗尽都会
 * 落地清理。
 *
 * <p>线程模型：单客户端主线程驱动；方法全部 {@code synchronized} 保护以容忍
 * Fabric DISCONNECT 兜底回调跨线程触发。
 */
public final class ClientRecoveryState {

    private static final ClientRecoveryState INSTANCE = new ClientRecoveryState();

    /** 单例：客户端主线程 + Fabric DISCONNECT 兜底回调共用同一恢复状态机。 */
    public static ClientRecoveryState getInstance() {
        return INSTANCE;
    }

    public enum Phase { NONE, RECOVERING, RECOVERED, TERMINAL }

    private Phase phase = Phase.NONE;
    private boolean cleanupConsumed = false;
    private long deadlineMs;

    public synchronized void begin(long deadlineMs) {
        // 终态单边：不可被拉回 RECOVERING
        if (phase == Phase.TERMINAL) {
            return;
        }
        this.phase = Phase.RECOVERING;
        this.deadlineMs = Math.max(0L, deadlineMs);
        this.cleanupConsumed = false;
    }

    /** 是否处于恢复中期/已恢复 —— 期间必须保留磁盘缓存/executor/save 链路。 */
    public boolean shouldSuppressFinalization() {
        Phase p = phase;
        return p == Phase.RECOVERING || p == Phase.RECOVERED;
    }

    /** plan §756 接口契约；与 {@link #shouldSuppressFinalization()} 同义。 */
    public boolean isRecovering() {
        return shouldSuppressFinalization();
    }

    public synchronized void markRecovered() {
        if (phase == Phase.RECOVERING) {
            phase = Phase.RECOVERED;
        }
        // NONE/RECOVERED/TERMINAL 忽略
    }

    public synchronized void markTerminal() {
        if (phase == Phase.TERMINAL) {
            return;
        }
        phase = Phase.TERMINAL;
    }

    /**
     * 新会话开始（用户经 ConnectScreen 发起新连接）时复位状态机。
     *
     * <p>TERMINAL 是会话内单边终态：恢复耗尽 markTerminal 后，若不复位，下一次正常断连
     * 会因 consumeTerminalCleanup 已被消费而跳过 finalize，且 F8 终态观察会在后续成功
     * 恢复窗口误判（phase 仍为 TERMINAL）。prepareInitialConnection 是「全新会话」的
     * 唯一入口（failover 候选连接的 ServerData 以 hassium-failover: 前缀跳过），故在此复位。
     */
    public synchronized void resetForNewSession() {
        phase = Phase.NONE;
        cleanupConsumed = false;
        deadlineMs = 0L;
    }

    /**
     * 返回 true 恰好一次。允许 {@code ClientLifecycleHelper.finalizeDisconnectIfTerminal()}
     * 在 TERMINAL 后做一次性的磁盘资源关闭。
     */
    public synchronized boolean consumeTerminalCleanup() {
        if (phase != Phase.TERMINAL) {
            return false;
        }
        if (cleanupConsumed) {
            return false;
        }
        cleanupConsumed = true;
        return true;
    }

    public Phase phase() {
        return phase;
    }

    public long deadlineMs() {
        return deadlineMs;
    }
}
