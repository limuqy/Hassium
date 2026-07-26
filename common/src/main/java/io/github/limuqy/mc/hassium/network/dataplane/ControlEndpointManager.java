package io.github.limuqy.mc.hassium.network.dataplane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Task 7 — 控制面候选端点的合并、去重与重连尝试编排（plan §797-838）。
 *
 * <p>职责：
 * <ul>
 *   <li>{@link #mergeBootstrapAndAdvertised(List, List)}：把用户手填 bootstrap 端点与服务器
 *       S2C 握手播发的 control 端点合并 — bootstrap 同坐标胜出（用户记下的是连接成功的地址）；
 *       advertised 中去掉与 bootstrap 同坐标的重复；按 {@code priority} 降序，最多保留 4 个。</li>
 *   <li>{@link #startRecovery(long)} 启动一次恢复：参数为相对毫秒窗口长度；内部换算为绝对
 *       deadline（{@code now + deadlineInMs}）。在 start 之前 {@link #nextCandidate()} 已经可
 *       返回 merge 之后的候选 — caller 可能只 merge 用于排序展示。</li>
 *   <li>{@link #nextCandidate()} 幂等返回当前高优先级候选；{@link #recordAttemptFailure(ControlEndpoint)}
 *       移除该候选后下次 nextCandidate 切到下一个。</li>
 *   <li>{@link #markConnected(ControlEndpoint)} 成功落块时记录连接候选；候选集与 deadline 不变。</li>
 * </ul>
 *
 * <p>不变量：候选列表最多 4 个（merge 时截断对应）；不动 vanilla 多人服务器地址。线程不安全 —
 * 由客户端主线程驱动（与 {@link ClientRecoveryState} 同线程）。
 */
public final class ControlEndpointManager {

    /** 上限 4：plan §838「最多四个控制候选」；避免 advertised 列表炸候选穷举窗口。 */
    static final int MAX_CANDIDATES = 4;

    private final List<ControlEndpoint> candidates = new ArrayList<>();
    /** 当前尝试过程：nextCandidate 返回的 top；同一候选在 recordAttemptFailure 期间重复读取幂等。 */
    private final LinkedHashMap<String, ControlEndpoint> remaining = new LinkedHashMap<>();
    private boolean recoveryStarted = false;
    private long deadlineMs = Long.MAX_VALUE;
    private LongSupplier clock = System::currentTimeMillis;

    public void mergeBootstrapAndAdvertised(List<ControlEndpoint> bootstrap,
                                            List<ControlEndpoint> advertised) {
        // 先按坐标去重；bootstrap 同坐标胜出
        LinkedHashMap<String, ControlEndpoint> byCoord = new LinkedHashMap<>();
        for (ControlEndpoint e : bootstrap) {
            byCoord.putIfAbsent(e.coordinateKey(), e);
        }
        // 再入 advertised：若坐标已被 bootstrap 占据则跳过，否则按 priority 与现有可竞争
        for (ControlEndpoint e : advertised) {
            byCoord.computeIfAbsent(e.coordinateKey(), k -> e);
        }
        // 按 priority 降序取前 MAX_CANDIDATES
        List<ControlEndpoint> ordered = new ArrayList<>(byCoord.values());
        ordered.sort(Comparator.comparingInt(ControlEndpoint::priority).reversed());
        candidates.clear();
        for (int i = 0; i < ordered.size() && i < MAX_CANDIDATES; i++) {
            candidates.add(ordered.get(i));
        }
        rebuildRemaining();
    }

    /**
     * 启动一次恢复：{@code deadlineInMs} 是相对当前时刻的窗口长度（毫秒）。
     * 内部换算为绝对 deadline（{@code now + deadlineInMs}），由 {@link System#currentTimeMillis()}
     * 兜底时钟。此后 {@link #nextCandidate()} 在窗口耗尽之前返回候选，过期一律返回空。
     */
    public void startRecovery(long deadlineInMs) {
        startRecoveryWithClock(deadlineInMs, System::currentTimeMillis);
    }

    /**
     * 可控时钟入口 — 测试注入确定性时间源。{@code deadlineInMs} 是相对当前时刻的窗口长度；
     * 绝对 deadline = {@code clock.getAsLong() + max(0, deadlineInMs)}。
     */
    void startRecoveryWithClock(long deadlineInMs, LongSupplier clock) {
        this.clock = clock;
        this.recoveryStarted = true;
        this.deadlineMs = clock.getAsLong() + Math.max(0L, deadlineInMs);
        rebuildRemaining();
        // 恢复窗口为非正时立即清空 remaining — nextCandidate 自然空
        if (deadlineInMs <= 0L) {
            remaining.clear();
        }
    }

    public Optional<ControlEndpoint> nextCandidate() {
        // 恢复已启动且 deadline 已过 → 不返回候选（即便还没 recordAttemptFailure）
        if (recoveryStarted && clock.getAsLong() >= deadlineMs) {
            return Optional.empty();
        }
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        // candidates 已按 priority 降序，rebuildRemaining 也按该顺序重建 → head 永远是当前最高候选
        return Optional.of(remaining.values().iterator().next());
    }

    public void recordAttemptFailure(ControlEndpoint endpoint) {
        if (endpoint == null) return;
        remaining.remove(endpoint.coordinateKey());
    }

    public void markConnected(ControlEndpoint endpoint) {
        // 标识成功；候选集不做隐式清理 —— 后续 markTerminal 由 Caller 控制
        // （Controller 一般同时调 startUdp，不重置 candidates 以便 logout 后再次重连仍有候选）
    }

    public List<ControlEndpoint> candidates() {
        return List.copyOf(candidates);
    }

    public boolean isRecoveryActive() {
        return recoveryStarted && clock.getAsLong() < deadlineMs && !remaining.isEmpty();
    }

    private void rebuildRemaining() {
        remaining.clear();
        for (ControlEndpoint e : candidates) {
            remaining.put(e.coordinateKey(), e);
        }
    }
}
