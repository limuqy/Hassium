package io.github.limuqy.mc.hassium.network.dataplane;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 3 — UDP 数据面服务端会话注册表与 lease 表。
 *
 * <p>职责（对应 plan §39、§385）：
 * <ul>
 *   <li>按 {@code (playerId, epoch)} 维度对 {@link ReliableDatagramSession} 分桶存档；同一键下保留
 *       注册顺序（用 {@link LinkedHashMap} 的迭代序），便于 per-epoch bulk router 在多 endpoint 间选路。</li>
 *   <li>由 {@link DataPlaneUdpServer} 在 Bind 成功后调用 {@code register}；
 *       在主 TCP {@code channelInactive}（hard disconnect）或 failover permit 颁布后调用
 *       {@code onPrimaryDisconnect} —— 把当前 epoch 下该玩家的所有会话切到 lease 状态，
 *       仅排干已 accepted 的帧、不接新 bulk，直到 lease 超时被 {@code expireLeases} 关闭。</li>
 *   <li>{@code replaceEpoch} 处理重登：新 epoch 来时旧 epoch 的所有会话立即关闭（旧 epoch 不留 lease），
 *       新 epoch 的 {@code register} 独立接受。</li>
 * </ul>
 *
 * <p>线程模型：本表方法可能在 UDP event loop 与 server tick 线程之间出现并发访问
 * （{@code register} 在 bind 路径，{@code expireLeases} 在 tick 路径），全部 {@code synchronized} 保护；
 * lease 起算/过期是单线程串行工作流，因此无需更激进并发原语。本表不持有任何 KCP/Netty 资源，
 * 仅对 {@link ReliableDatagramSession#markLease}/{@code close} 做协同。
 */
public final class DataPlaneSessionRegistry {

    /** 注册主键：{@code (playerId, epoch)}；hashCode/equals 严格遵循这两个字段。 */
    private static final class Key {
        private final UUID playerId;
        private final long epoch;
        Key(UUID p, long e) { this.playerId = p; this.epoch = e; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return epoch == k.epoch && Objects.equals(playerId, k.playerId);
        }
        @Override public int hashCode() { return Objects.hash(playerId, epoch); }
    }

    /** 待 lease 关闭项：{@code (playerId, epoch)} 与 lease 截止时刻。 */
    private static final class PendingLease {
        final UUID playerId;
        final long epoch;
        final long expireAt;
        PendingLease(UUID p, long e, long expireAt) {
            this.playerId = p; this.epoch = e; this.expireAt = expireAt;
        }
    }

    private final Map<Key, List<ReliableDatagramSession>> sessions = new LinkedHashMap<>();
    private final List<PendingLease> pendingLeases = new ArrayList<>();

    /** 注册新会话；同 (playerId, epoch) 下保留注册顺序。 */
    public synchronized void register(ReliableDatagramSession session) {
        Objects.requireNonNull(session, "session");
        Key k = new Key(session.playerId(), session.epoch());
        sessions.computeIfAbsent(k, x -> new ArrayList<>()).add(session);
    }

    /** 返回该 {@code (playerId, epoch)} 下的会话列表（不可变快照；返回空列表而非 null）。 */
    public synchronized List<ReliableDatagramSession> sessions(UUID playerId, long epoch) {
        List<ReliableDatagramSession> list = sessions.get(new Key(playerId, epoch));
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * 返回该 {@code playerId} 下所有 epoch 的会话快照（用于 bulk router 在不知当前 epoch 时扫活集）。
     * 排除已 closed；可能含 lease active（router 用 {@link BulkRouteTarget#isLeaseActive(long)} 过滤）。
     */
    public synchronized List<ReliableDatagramSession> sessionsByPlayer(UUID playerId) {
        List<ReliableDatagramSession> out = new ArrayList<>();
        for (Map.Entry<Key, List<ReliableDatagramSession>> e : sessions.entrySet()) {
            if (!Objects.equals(e.getKey().playerId, playerId)) continue;
            for (ReliableDatagramSession s : e.getValue()) {
                // Lease 会话只排干 bind 前已接受的 KCP 发送队列，绝不重新承接 bulk 路由。
                if (!s.isClosed() && !s.isLeaseDraining()) out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 重登/epoch 推进：立即关闭旧 epoch 的所有会话（无线 lease）并移除该键；
     * 调用方随后可对新 epoch 调用 {@link #register}。未知 {@code playerId}/{@code epoch} 为无害 no-op。
     */
    public synchronized void replaceEpoch(UUID playerId, long newEpoch) {
        // 关键：必须按 (playerId, *) 而非 (playerId, newEpoch) 来扫——旧 epoch 才是被关闭目标；
        // new epoch 在调用方后续 register 时建立，本方法不预创建。
        for (List<ReliableDatagramSession> bucket : new ArrayList<>(sessions.values())) {
            for (ReliableDatagramSession s : bucket) {
                if (Objects.equals(s.playerId(), playerId) && s.epoch() != newEpoch) {
                    s.close();
                }
            }
        }
        sessions.keySet().removeIf(k -> Objects.equals(k.playerId, playerId) && k.epoch != newEpoch);
        // 同步撤销该玩家所有未到期的 lease 项（lease 期被超时关闭，不跨 epoch 保留）
        pendingLeases.removeIf(pl -> Objects.equals(pl.playerId, playerId) && pl.epoch != newEpoch);
    }

    /**
     * 主 TCP 断开：对该 {@code (playerId, epoch)} 当前所有会话起算 {@code leaseMs} lease，
     * 用于排干已 accepted 的应用帧；不在 lease 期内继续接受新 bulk（由 router 层根据
     * {@link ReliableDatagramSession#isLeaseActive(long)} 做拒绝决策）。
     */
    public synchronized void onPrimaryDisconnect(UUID playerId, long epoch, long nowMs, long leaseMs) {
        if (leaseMs <= 0L) {
            return;
        }
        beginLease(playerId, epoch, nowMs + leaseMs);
    }

    /**
     * Failover permit 已签发：把该 {@code (playerId, epoch)} 切入排干，直到绝对 deadline。
     * 新 bulk 会被 {@link #sessionsByPlayer(UUID)} 排除；既有 KCP 队列留到
     * {@link #expireLeases(long)} 在 deadline 关闭。
     */
    public synchronized void beginFailoverLease(UUID playerId, long epoch, long expiryMs) {
        beginLease(playerId, epoch, expiryMs);
    }

    private void beginLease(UUID playerId, long epoch, long expiryMs) {
        List<ReliableDatagramSession> bucket = sessions.get(new Key(playerId, epoch));
        if (bucket == null) {
            return;
        }
        // 同键 lease 必须以最新 permit 覆盖，避免旧 deadline 提前关闭新 lease。
        pendingLeases.removeIf(pl -> Objects.equals(pl.playerId, playerId) && pl.epoch == epoch);
        pendingLeases.add(new PendingLease(playerId, epoch, expiryMs));
        for (ReliableDatagramSession s : bucket) {
            s.markLeaseUntil(expiryMs);
        }
    }

    /**
     * 推进 lease 表：关闭所有已逾期的 (playerId, epoch) 会话，把它们从 {@code sessions} 移除。
     * 单调调用（tick 线程上串行）；幂等。
     */
    public synchronized void expireLeases(long nowMs) {
        if (pendingLeases.isEmpty()) {
            return;
        }
        for (PendingLease pl : pendingLeases) {
            if (nowMs >= pl.expireAt) {
                List<ReliableDatagramSession> bucket = sessions.get(new Key(pl.playerId, pl.epoch));
                if (bucket != null) {
                    for (ReliableDatagramSession s : bucket) {
                        s.close();
                    }
                    bucket.clear();
                    sessions.remove(new Key(pl.playerId, pl.epoch));
                }
            }
        }
        pendingLeases.removeIf(pl -> nowMs >= pl.expireAt);
    }
}
