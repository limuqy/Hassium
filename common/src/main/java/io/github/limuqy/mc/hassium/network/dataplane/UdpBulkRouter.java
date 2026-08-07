package io.github.limuqy.mc.hassium.network.dataplane;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 4 — UDP 数据面服务端 bulk 路由（取代 PoC {@link BulkRouter} 的 TCP 路径语义）。
 *
 * <p>核心语义（plan §495-506）：
 * <ul>
 *   <li>{@code share} 模式：PRIMARY + 健康 DATA 候选按权重做 WRR；选到 PRIMARY 时返回 {@link RouteDecision#PRIMARY}
 *       由 caller 走原 TCP；选到 DATA 时调用 {@link BulkRouteTarget#enqueueAuthenticated(int, byte[])}
 *       成功返回 {@link RouteDecision#DATA_SENT}；失败 DROPPED。</li>
 *   <li>{@code exclusive} 模式：仅 DATA 候选；无候选或 enqueue 失败累计 {@code consecutiveDrops}，
 *       达到 {@code degradeAfterDrops} 时下一次直接返回 {@link RouteDecision#PRIMARY} 并把
 *       {@link PlayerSessions#degraded} 置 true（caller 后续直接走 Primary 不再咨询 router）。</li>
 *   <li>health 过滤：排除 {@code isClosed()}、未 lease active、{@code !isWritable()}、{@code !isHealthy()}
 *       （SRTT 超 {@code hardRttMs} 视为不健康）。</li>
 *   <li>WRR 状态住在 {@link PlayerSessions}（mutable counter）—— 不是 per-call collection；
 *       任何 enqueue 成功即时清零 {@code consecutiveDrops}，degraded 之后路由器恒返回 PRIMARY。</li>
 * </ul>
 *
 * <p>不动 NetworkStats；主/数据字节埋点在调用站点（{@link DataPlaneUdpServer#tryRouteBulk}）一次记。
 *
 * <p>不变量：本类无状态（{@code hardRttMs} 是只读配置），可被多玩家并发调用；
 * {@link PlayerSessions} 由 caller 在 per-player 上下文持有，router 仅接收并修改其可变计数。
 * 同一 {@code PlayerSessions} 的决策与 {@code refresh} 以 ps monitor 互斥——调用方
 * （{@code DataPlaneUdpServer#tryRouteBulk}）会从 {@code ServerChunkPushManager} 的多线程
 * pushPool 并发到达，无锁时 {@code curWeights} 的 check-then-act 会产生 AIOOBE。
 */
public final class UdpBulkRouter {

    /** 路由决策结果。 */
    public enum RouteDecision {
        /** 已入队 DATA。caller 不应再走 Primary。 */
        DATA_SENT,
        /** 选到 Primary 候选（share）或 exclusive 降级；caller 应在 Primary TCP 上发本帧。 */
        PRIMARY,
        /** 无候选 AND 未到降级阈值；caller 不应走 Primary（exclusive 语义），记录一次 drop。 */
        DROPPED
    }

    /**
     * 单个 (player, epoch) 的 router 工作集——WRR 累计权重、连续 drop、degraded flag。
     * 由 {@link DataPlaneUdpServer} 在玩家首次入帧时构造并缓存（per-player），router 仅读写其可变字段。
     */
    public static final class PlayerSessions {
        private List<BulkRouteTarget> sessions;
        long wrrAccum = 0L;
        private int consecutiveDrops = 0;
        private boolean degraded = false;
        /** smoke marker：每 per-player workspace 第一个成功 DATA_SENT 仅打一次，避免热路径刷日志。 */
        boolean dataSentMarkerEmitted = false;
        /** Interleaved WRR 每候选 current-weight；与 {@link #sessions} 列表对齐（idx 相同）。 */
        long[] curWeights = new long[0];

        private PlayerSessions(List<BulkRouteTarget> sessions) {
            this.sessions = List.copyOf(sessions);
        }

        /** 从给定 sessions 快照构造工作集（WRR 状态从 0 开始）。 */
        public static PlayerSessions of(List<? extends BulkRouteTarget> sessions) {
            return new PlayerSessions(new ArrayList<>(sessions));
        }

        /**
         * router 调用前刷新 sessions 快照（保留 wrrAccum / drops / degraded；curWeights 按新长度重整）。
         *
         * <p>与 {@link UdpBulkRouter} 的决策方法共用 ps monitor：{@code tryRouteBulk} 会从
         * {@code ServerChunkPushManager} 的多线程 pushPool 并发到达（同一玩家的多个 chunk 任务），
         * 若 refresh 与 wrrPickShared 的 curWeights check-then-act 不互斥，会踩到数组长度
         * 被并发替换导致的 AIOOBE。
         *
         * <p>curWeights 按 {@code size + 1} 分配：share 模式的 {@code wrrPickShared} 需要
         * PRIMARY 虚拟槽位（检查 {@code length != n + 1}），exclusive 的 {@code wrrPick} 只要
         * {@code length >= n}——两个消费方都免重分配，避免每 chunk 的数组抖动。
         */
        public synchronized void refresh(List<? extends BulkRouteTarget> fresh) {
            this.sessions = List.copyOf(fresh);
            if (this.curWeights.length != fresh.size() + 1) {
                this.curWeights = new long[fresh.size() + 1];
            }
        }

        public List<BulkRouteTarget> sessions() { return sessions; }
        public int consecutiveDrops() { return consecutiveDrops; }
        public boolean degraded() { return degraded; }
    }

    private final long hardRttMs;

    public UdpBulkRouter(long hardRttMs) {
        this.hardRttMs = hardRttMs <= 0 ? Long.MAX_VALUE : hardRttMs;
    }

    /**
     * 选择一个健康 DATA target（不入队）。plan §473 使用。
     * 返回 null 表示无健康 DATA 候选（caller 视 §498 决定走 share 的 PRIMARY 或 exclusive 的 drop）。
     */
    public BulkRouteTarget select(PlayerSessions ps, String mode, int primaryWeight, int degradeAfterDrops) {
        synchronized (ps) {
            if (ps.degraded) return null;
            List<BulkRouteTarget> healthy = healthySessions(ps);
            if (healthy.isEmpty()) return null;
            if ("share".equals(mode)) {
                // PRIMARY + DATA 同时按权重 WRR；这里只返回 DATA 选中（PRIMARY 路径由 route 决策）。
                BulkRouteTarget pick = wrrPickShared(ps, healthy, primaryWeight);
                return pick == null ? null : pick;
            }
            // exclusive：仅候选间 WRR（无 PRIMARY）
            return wrrPick(healthy, ps);
        }
    }

    /**
     * 计算路由决策并（若选到 DATA）执行入队。{@code mode}/{@code primaryWeight}/{@code degradeAfterDrops}
     * 与 PoC {@link BulkRouter#sendBulk} 一致语义。{@code type}/{@code payload} 仅在 DATA_SENT 路径入队。
     *
     * <p>仅返回决策枚举，丢失选中 target；若 caller 需要 endpoint 上下文（如 §14 step 4 的
     * per-portIdx 发送指标），请用 {@link #routeAndPick}，返回 {@link RouteOutcome} 含选中 target。
     */
    public RouteDecision route(PlayerSessions ps, String mode, int primaryWeight,
                               int degradeAfterDrops, int type, byte[] payload) {
        return routeAndPick(ps, mode, primaryWeight, degradeAfterDrops, type, payload).decision();
    }

    /**
     * 与 {@link #route} 同语义，但返回 {@link RouteOutcome}，暴露命中 DATA 时的 chosen target
     * （DATA_SENT 时非 null；PRIMARY/DROPPED 时为 null），供 caller（{@code DataPlaneUdpServer.tryRouteBulk}）
     * 在调用站点记 per-portIdx 发送指标（§14 v2 step 4 重建）。
     *
     * <p>不变量：router 自身仍在 javadoc「不动 NetworkStats」原则下，仅 {@code enqueueAuthenticated}
     * 与 WRR 计数副作用；metrics 埋点由 caller 负责。
     */
    public RouteOutcome routeAndPick(PlayerSessions ps, String mode, int primaryWeight,
                                    int degradeAfterDrops, int type, byte[] payload) {
        // ps monitor：refresh 与同一玩家的并发 bulk 决策互斥（见 PlayerSessions.refresh javadoc）。
        synchronized (ps) {
            if (ps.degraded) {
                return new RouteOutcome(RouteDecision.PRIMARY, null);
            }
            boolean share = "share".equals(mode);
            List<BulkRouteTarget> healthy = healthySessions(ps);

            if (healthy.isEmpty()) {
                // 无候选
                if (share) {
                    return new RouteOutcome(RouteDecision.PRIMARY, null); // share 走 Primary 而不 drop
                }
                // exclusive: 累加 drop，达阈值降级
                return new RouteOutcome(exclusiveDropOrDegrade(ps, degradeAfterDrops), null);
            }

            if (share) {
                // 把 PRIMARY 当一个隐式虚拟候选 weight=primaryWeight，与 DATA 候选一同 WRR。
                // 为保证稳定：累计权重每 tick 重整；命中 PRIMARY → PRIMARY 决策；命中 DATA → 入队。
                BulkRouteTarget picked = wrrPickShared(ps, healthy, primaryWeight);
                if (picked == null) {
                    return new RouteOutcome(RouteDecision.PRIMARY, null); // 命中 Primary
                }
                boolean ok = picked.enqueueAuthenticated(type, payload);
                if (ok) {
                    ps.consecutiveDrops = 0;
                    if (!ps.dataSentMarkerEmitted) {
                        ps.dataSentMarkerEmitted = true;
                        org.slf4j.LoggerFactory.getLogger("HassiumSmokeTest")
                                .info("HassiumSmokeTest:UDP_FAILOVER UDP_WRR_OK player-mode=share decision=DATA_SENT");
                    }
                    return new RouteOutcome(RouteDecision.DATA_SENT, picked);
                }
                // enqueue 失败：等价 drop。share 模式下，本帧也退到 Primary（保守策略，不丢业务）。
                return new RouteOutcome(RouteDecision.PRIMARY, null);
            }

            // exclusive
            BulkRouteTarget target = wrrPick(healthy, ps);
            boolean ok = target.enqueueAuthenticated(type, payload);
            if (ok) {
                ps.consecutiveDrops = 0;
                if (!ps.dataSentMarkerEmitted) {
                    ps.dataSentMarkerEmitted = true;
                    org.slf4j.LoggerFactory.getLogger("HassiumSmokeTest")
                            .info("HassiumSmokeTest:UDP_FAILOVER UDP_WRR_OK player-mode=exclusive decision=DATA_SENT");
                }
                return new RouteOutcome(RouteDecision.DATA_SENT, target);
            }
            return new RouteOutcome(exclusiveDropOrDegrade(ps, degradeAfterDrops), null);
        }
    }

    /**
     * routeAndPick 的返回 carrier：决策 + 命中 target（仅在 {@link RouteDecision#DATA_SENT} 时非 null）。
     * 用于让 caller 在调用站点取 endpointId 以记 §14 step 4 per-portIdx 发送指标。
     */
    public record RouteOutcome(RouteDecision decision, BulkRouteTarget chosenOrNull) {}

    /** exclusive 路径下：drop 累计；达到 {@code degradeAfterDrops} 当次仍返回 DROPPED，下一次返回 PRIMARY + degraded=true。 */
    private RouteDecision exclusiveDropOrDegrade(PlayerSessions ps, int degradeAfterDrops) {
        // 累计阈值语义（plan §482）：drop 第 N 次（N == degradeAfterDrops）仍 DROPPED；第 N+1 次入函数时
        // 上一次累计已达阈值 → 直接降级返回 PRIMARY 并 degraded=true。
        if (ps.consecutiveDrops >= degradeAfterDrops) {
            ps.degraded = true;
            return RouteDecision.PRIMARY;
        }
        ps.consecutiveDrops++;
        return RouteDecision.DROPPED;
    }

    /** 当前时刻健康会话快照。 */
    private List<BulkRouteTarget> healthySessions(PlayerSessions ps) {
        long now = System.currentTimeMillis();
        List<BulkRouteTarget> out = new ArrayList<>(ps.sessions.size());
        for (BulkRouteTarget t : ps.sessions) {
            if (t.isClosed() || !t.isWritable() || !t.isHealthy() || !t.isLeaseActive(now)) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * 经典 interleaved WRR：在 {@code healthy} 子上累加 current weight，选 max，扣 total；PRIMARY 作为
     * 虚拟候选（idx == sessions.size()）。健康过滤掉的会话不累加（冻结其 curW），保证选过的会话不丢公平性。
     * 命中 PRIMARY → 返回 null（caller 退到 Primary TCP，不入队）。
     */
    private BulkRouteTarget wrrPickShared(PlayerSessions ps, List<BulkRouteTarget> healthy, int primaryWeight) {
        int n = ps.sessions.size();
        if (ps.curWeights.length != n + 1) {
            ps.curWeights = new long[n + 1];
        }
        long[] cw = ps.curWeights;
        int primarySlot = n;

        // 1) 累加：健康会话累基 effective weight；PRIMARY 累 primaryWeight。
        long total = 0L;
        for (int i = 0; i < n; i++) {
            BulkRouteTarget t = ps.sessions.get(i);
            if (!healthy.contains(t)) continue;           // 不健康 -> 冻结 curW[i]
            int w = Math.max(1, effectiveWeight(t));
            cw[i] += w;
            total += w;
        }
        cw[primarySlot] += Math.max(0, primaryWeight);
        total += Math.max(0, primaryWeight);
        if (total <= 0) return null;

        // 2) argmax
        int pickIdx = primarySlot;
        long maxW = cw[primarySlot];
        int tieBreak = 0;
        for (int i = 0; i < n; i++) {
            BulkRouteTarget t = ps.sessions.get(i);
            if (!healthy.contains(t)) continue;
            if (cw[i] > maxW) {
                maxW = cw[i];
                pickIdx = i;
                tieBreak = i;
            } else if (cw[i] == maxW && tieBreak++ == 0 && pickIdx == primarySlot) {
                // 首轮 PRIMARY 与某 candidate 相等时，偏序到 candidate（避免 PRIMARY 抢占 cycle 起始）
                pickIdx = i;
            }
        }
        cw[pickIdx] -= total;
        if (pickIdx == primarySlot) return null;
        return ps.sessions.get(pickIdx);
    }

    /** 健康 candidates 之间的纯 WRR（无 Primary）。{@code exclusiveOnly=true} 时 WRR 仅在 candidates 间循环。 */
    private BulkRouteTarget wrrPick(List<BulkRouteTarget> candidates, PlayerSessions ps) {
        if (candidates.isEmpty()) return null;
        int n = ps.sessions.size();
        if (ps.curWeights.length < n) {
            ps.curWeights = new long[n];
        }
        long[] cw = ps.curWeights;
        long total = 0L;
        int pickIdx = -1;
        long maxW = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (!candidates.contains(ps.sessions.get(i))) continue;
            int w = Math.max(1, effectiveWeight(ps.sessions.get(i)));
            cw[i] += w;
            total += w;
            if (cw[i] > maxW) { maxW = cw[i]; pickIdx = i; }
        }
        if (pickIdx < 0) return null;
        cw[pickIdx] -= total;
        return ps.sessions.get(pickIdx);
    }


    /** SRTT 高 → 按线性公式压低 effective weight；SRTT = hardRttMs 时权重为 1。 */
    private int effectiveWeight(BulkRouteTarget c) {
        int base = c.weight();
        if (base < 1) base = 1;
        long srtt = c.metrics().srttMs();
        if (srtt <= 0 || srtt >= hardRttMs) {
            // 已被 isHealthy 过滤；这里仅兜底，硬门限 → 排除不应到此。
            return srtt >= hardRttMs ? 1 : base;
        }
        // 线性扣减：effective = base * (1 - srtt/hardRtt)。SRTT=0 → base；SRTT→hardRtt → 0（钳到 1）。
        long scaled = base - (base * srtt) / hardRttMs;
        return scaled < 1 ? 1 : (int) scaled;
    }
}
