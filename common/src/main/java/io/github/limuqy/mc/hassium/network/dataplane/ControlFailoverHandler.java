package io.github.limuqy.mc.hassium.network.dataplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 6 — 服务端控制 failover 授权机。
 *
 * <p>当主 TCP 控制连接停滞但仍存活（未收到 {@code channelInactive}）时，客户端可以通过已鉴权的
 * UDP 会话发出 {@link DataPlaneFrame#TYPE_FAILOVER_REQUEST} 请求切换：本机依据
 * <em>(player last-control-activity, epoch, UDP 会话存在性)</em> 三个维度授权并关闭旧 master，
 * 同步颁布 failover permit；后续由 {@link DataPlaneSessionRegistry#beginFailoverLease} 起 lease，
 * 让已 accepted 的净流量排干，{@code expireLeases} 到期再彻底关闭该 epoch 的所有会话。
 *
 * <p>本类位于 common——MUST NOT import Fabric/Forge/Minecraft API。真实 master Connection 的持有与
 * 关闭委托给 loader 提供 Runnable（见 {@link #registerControlConnection}）。
 *
 * <p>线程模型：{@code recordControlActivity} 在服务器 tick 线程被定期注入；{@code requestFailover}
 * 在 UDP event loop 上接收 TYPE_FAILOVER_REQUEST 时被触发；两者可能并发，{@link PlayerState}
 * 实例字段在 {@code synchronized(st)} 块内修改。
 */
public final class ControlFailoverHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ControlFailover");

    /**
     * 当主连接 last-control-activity 距 {@code now} 超过此阈值则视为「stalled」可考虑 failover，
     * 以防止客户端在主连接依然在转发控制流时强行切换。可经 {@link #setControlStallMs(long)} 覆写。
     */
    public static final long DEFAULT_CONTROL_STALL_MS = 6_000L;

    /**
     * Failover permit 自颁布起的有效期（毫秒）。客户端应在此窗口内完成重连到控制候选端点；
     * 超期由 {@code beginFailoverLease}/{@code expireLeases} 关闭旧 epoch 的会话。
     * 可经 {@link #setFailoverPermitTtlMs(long)} 覆写。
     */
    public static final long DEFAULT_FAILOVER_PERMIT_TTL_MS = 30_000L;

    /** Authorized failover outcome. */
    public enum FailoverResult {
        PERMITTED,
        REJECTED_ACTIVE,
        NO_CONNECTION,
        NO_UDP_SESSION,
        EPOCH_MISMATCH
    }

    /** Failover permit 记账（不传 wire 安全材料——permits 表仅记录授权元数据，wire 帧分发由上层处理）。 */
    public record Permit(UUID playerId, long epoch, int endpointId, long permitMs) {}

    /** Per-player 状态：最近一次有效控制活动的时间戳、已注册的 master-close 句柄、UDP 会话存在性。 */
    private static final class PlayerState {
        final UUID playerId;
        long epoch;
        long lastControlActivityMs;
        Runnable masterClose;
        boolean udpSessionPresent;
        PlayerState(UUID playerId) { this.playerId = playerId; }
    }

    /** 单实例（生产环境）。 */
    private static final ControlFailoverHandler INSTANCE = new ControlFailoverHandler();
    public static ControlFailoverHandler getInstance() { return INSTANCE; }

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final List<Permit> permits = Collections.synchronizedList(new ArrayList<>());
    private volatile long controlStallMs = DEFAULT_CONTROL_STALL_MS;
    private volatile long failoverPermitTtlMs = DEFAULT_FAILOVER_PERMIT_TTL_MS;

    private ControlFailoverHandler() {}

    /** 测试便利工厂：返回隔离实例，由 caller 自行 registerControlConnection。 */
    static ControlFailoverHandler forTest() {
        return new ControlFailoverHandler();
    }

    /** 测试便利工厂：直接绑定玩家 master close 句柄。 */
    static ControlFailoverHandler forTest(UUID playerId, long epoch, Runnable masterClose) {
        ControlFailoverHandler h = new ControlFailoverHandler();
        if (masterClose != null) {
            h.registerControlConnection(playerId, epoch, masterClose);
        }
        return h;
    }

    /**
     * 接受一个新的 TCP master 并为其分配递增 epoch。旧 UDP 授权被撤销；调用方必须用返回值
     * 写入握手 S2C tail，客户端才能建立对应的 KCP 会话。
     */
    public long beginControlConnection(UUID playerId, Runnable masterClose) {
        PlayerState st = states.computeIfAbsent(playerId, PlayerState::new);
        synchronized (st) {
            st.epoch = st.epoch == Long.MAX_VALUE ? 1L : Math.max(1L, st.epoch + 1L);
            st.lastControlActivityMs = System.currentTimeMillis();
            st.masterClose = masterClose;
            st.udpSessionPresent = false;
            return st.epoch;
        }
    }

    /** 当前控制 epoch；已签发 permit 而尚待 disconnect 清理时仍保留其 epoch。 */
    public long currentEpoch(UUID playerId) {
        PlayerState st = states.get(playerId);
        if (st == null) return 0L;
        synchronized (st) {
            return st.epoch;
        }
    }

    /**
     * 服务端注册主控制连接（初始化玩家 state）；caller 在
     * ServerGamePacketListenerImpl 初始化时调用；传 {@code null} 表示清除该玩家 master。
     */
    public void registerControlConnection(UUID playerId, long epoch, Runnable masterClose) {
        PlayerState st = states.computeIfAbsent(playerId, PlayerState::new);
        synchronized (st) {
            st.epoch = epoch;
            st.masterClose = masterClose;
        }
    }

    public void remove(UUID playerId) {
        states.remove(playerId);
    }

    /** 服务端在处理任意入站控制流量（packet/tick）时调，刷新 lastControlActivity。 */
    public void recordControlActivity(UUID playerId, long epoch, long nowMs) {
        PlayerState st = states.computeIfAbsent(playerId, PlayerState::new);
        synchronized (st) {
            st.epoch = epoch;
            st.lastControlActivityMs = nowMs;
        }
    }

    /** 测试辅助：宣告某玩家某 epoch 下存在已鉴权 UDP 会话。生产环境通过 DataPlaneUdpServer 绑定回写状态。 */
    void declareUdpSessionForTest(UUID playerId, long epoch, int endpointId) {
        PlayerState st = states.computeIfAbsent(playerId, PlayerState::new);
        synchronized (st) {
            st.epoch = epoch;
            st.udpSessionPresent = true;
        }
    }

    /** Production callback：由 DataPlaneUdpServer 在 BindRequest 接受后调用，记录 UDP 会话已建立。 */
    public void onUdpSessionEstablished(UUID playerId, long epoch) {
        PlayerState st = states.computeIfAbsent(playerId, PlayerState::new);
        synchronized (st) {
            // 已有 master 时，旧 epoch bind 不能回滚状态或获得 failover 权限。
            if (st.masterClose != null && st.epoch != epoch) {
                return;
            }
            st.epoch = epoch;
            st.udpSessionPresent = true;
        }
    }

    /** Production callback：由 DataPlaneUdpServer 在该 (playerId, epoch) 不再有任何活会话时调用。 */
    public void onUdpSessionClosed(UUID playerId, long epoch) {
        PlayerState st = states.get(playerId);
        if (st == null) return;
        synchronized (st) {
            if (st.epoch == epoch) {
                st.udpSessionPresent = false;
            }
        }
    }

    public synchronized List<Permit> permits() {
        return List.copyOf(permits);
    }

    public void setControlStallMs(long ms) { this.controlStallMs = Math.max(0L, ms); }
    public void setFailoverPermitTtlMs(long ms) { this.failoverPermitTtlMs = Math.max(0L, ms); }
    public long failoverPermitTtlMs() { return failoverPermitTtlMs; }

    /**
     * 客户端经 UDP 发出 TYPE_FAILOVER_REQUEST 时服务端入口。
     * 决策顺序：NO_CONNECTION → NO_UDP_SESSION → EPOCH_MISMATCH → REJECTED_ACTIVE → PERMITTED。
     * PERMITTED 后：关闭旧 master（仅一次）、记录 permit；lease 由 {@link DataPlaneSessionRegistry}
     * 后续 beginFailoverLease 处理。
     */
    public FailoverResult requestFailover(UUID playerId, long epoch, int requestedEndpointId, long nowMs) {
        PlayerState st = states.get(playerId);
        if (st == null || st.masterClose == null) {
            return FailoverResult.NO_CONNECTION;
        }
        synchronized (st) {
            if (!st.udpSessionPresent) {
                return FailoverResult.NO_UDP_SESSION;
            }
            if (st.epoch != epoch) {
                return FailoverResult.EPOCH_MISMATCH;
            }
            long elapsed = nowMs - st.lastControlActivityMs;
            if (elapsed < controlStallMs) {
                return FailoverResult.REJECTED_ACTIVE;
            }
            Runnable close = st.masterClose;
            try {
                close.run();
            } catch (Throwable t) {
                LOGGER.warn("Control failover: closing old master failed for player={}", playerId, t);
            }
            permits.add(new Permit(playerId, epoch, requestedEndpointId, nowMs));
            st.masterClose = null; // 单次生效，避免重复关闭
            return FailoverResult.PERMITTED;
        }
    }
}
