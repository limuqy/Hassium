package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task 5 — 客户端 UDP 数据面生命周期门面。Per-plan §619：{@link #startUdp(UUID, long, UdpDataPlaneHandshakeTail.S2CTail)}
 * 在 S2C 握手尾部 accept 后由 Fabric 客户端调用；{@link #stopUdp()} 在控制连接终断、断开重连或关机时调用。
 *
 * <p>独立于 {@link DataPlaneClientBundle}：客户端只需经本类拿到当前 bundle 引用并驱动 tick/lease；所有 PoC
 * 静态计数器（{@link DataPlaneClientBundle#getBulkFramesData} 等）保持兼容。
 *
 * <p>线程模型：startUdp/stopUdp 在 Minecraft 主线程调用，禁止从 Netty event loop 反向调用。
 * {@link #tick(long)} 同样由主线程侧主动驱动（PoC 阶段由调试/心跳触发；Task 7 接入恢复态后改为周期任务）。
 */
public final class DataPlaneClientLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneClient");

    private static final DataPlaneClientLifecycle INSTANCE = new DataPlaneClientLifecycle();
    private final Object lock = new Object();
    private DataPlaneClientBundle bundle;
    private long leaseDeadlineMs;
    private volatile long epoch;

    private DataPlaneClientLifecycle() {}

    public static DataPlaneClientLifecycle getInstance() { return INSTANCE; }

    /**
     * 启动 UDP 数据面；若已存在 bundle 则先 shutdown 并替换（新 epoch 即重连）。
     *
     * @param playerId 客户端玩家 UUID
     * @param epoch    server 颁发的 connectionEpoch
     * @param tail     服务端 S2C 尾部；若 {@link UdpDataPlaneHandshakeTail.S2CTail#hasUdpDataplane()} 为 false
     *                 则直接 no-op（保持旧 client 行为）
     */
    public void startUdp(UUID playerId, long epoch,
                        UdpDataPlaneHandshakeTail.S2CTail tail) {
        if (tail == null || !tail.hasUdpDataplane()) {
            DebugLogger.debug(LogType.NETWORK, "DataPlaneClient: tail disabled, skip UDP start");
            return;
        }
        List<UdpDataPlaneHandshakeTail.UdpListenerGroup> groups = groupsForTail(tail);
        if (groups.isEmpty() || tail.token() == null || tail.token().length != 16) {
            DebugLogger.info(LogType.NETWORK,
                    "DataPlaneClient: tail accepted but missing listener groups/token (groups={} token={})",
                    groups.size(), tail.token() == null ? -1 : tail.token().length);
            return;
        }

        synchronized (lock) {
            DataPlaneClientBundle existing = this.bundle;
            if (existing != null) {
                if (this.epoch == epoch && existing.isBound()) {
                    // 同 epoch 且已 bound → 幂等返回，避免重连抖动
                    DebugLogger.debug(LogType.NETWORK, "DataPlaneClient: already bound for epoch={} (idempotent)", epoch);
                    return;
                }
                existing.shutdown();
            }

            DataPlaneClientBundle nb = new DataPlaneClientBundle();
            nb.connectAndBind(playerId, epoch, tail.token(), groups);
            this.bundle = nb;
            this.epoch = epoch;
            // 服务端口 lease 默认全程；Task 7 接入后将由 ControlReconnect 复写
            this.leaseDeadlineMs = Long.MAX_VALUE;
            LOGGER.info("DataPlaneClient: UDP data-plane binding player={} epoch={} listenerGroups={}",
                    playerId, epoch, groups.size());
        }
    }

    /** 新尾部优先使用 listener groups；旧尾部每个 legacy endpoint 退化为一个单 candidate group。 */
    static List<UdpDataPlaneHandshakeTail.UdpListenerGroup> groupsForTail(
            UdpDataPlaneHandshakeTail.S2CTail tail) {
        if (tail == null) {
            return List.of();
        }
        if (!tail.udpListenerGroups().isEmpty()) {
            return List.copyOf(tail.udpListenerGroups());
        }
        List<UdpDataPlaneHandshakeTail.UdpListenerGroup> groups = new ArrayList<>();
        for (UdpDataPlaneHandshakeTail.UdpEndpointInfo endpoint : tail.udpEndpoints()) {
            groups.add(new UdpDataPlaneHandshakeTail.UdpListenerGroup(
                    endpoint.endpointId(), endpoint.weight(), List.of(
                    new UdpDataPlaneHandshakeTail.UdpReachableEndpoint(
                            endpoint.host(), endpoint.port(), 0))));
        }
        return List.copyOf(groups);
    }

    /**
     * 主动停止 UDP 数据面。Task 7 在重连编排中调用以清理旧 epoch 的 bundle。
     *
     * @param keepLease true=保留 lease 期望（Task 6 路径：主连接断开但 UDP 健康 → 仅请求 failover）；
     *                  false=完全掉线/收尾。
     */
    public void stopUdp(boolean keepLease) {
        synchronized (lock) {
            DataPlaneClientBundle b = this.bundle;
            this.bundle = null;
            if (b != null) {
                try { b.shutdown(); } catch (Throwable ignored) {}
            }
            if (!keepLease) {
                this.leaseDeadlineMs = 0L;
                this.epoch = 0L;
            }
        }
    }

    /** 当前是否活跃 bound；供 Task 7 重连判定。 */
    public boolean isBound() {
        synchronized (lock) {
            return bundle != null && bundle.isBound();
        }
    }

    public long currentEpoch() {
        return epoch;
    }

    /**
     * 心跳 tick：驱动 KCP internal update + lease 过期检查；由主线程侧周期性调用。
     * 实现侧保持往后空调用——Task 7 重连状态接入后真正负责周期调度。
     */
    public void tick(long nowMs) {
        DataPlaneClientBundle b;
        long leaseDeadline;
        synchronized (lock) {
            b = this.bundle;
            leaseDeadline = this.leaseDeadlineMs;
        }
        // lease 过期 → 主动 shutdown
        if (nowMs >= leaseDeadline) {
            stopUdp(false);
            return;
        }
        try {
            b.tick(nowMs);
        } catch (Throwable t) {
            LOGGER.warn("DataPlaneClient: tick failure", t);
        }
    }

    /** Task 6 控制 failover 场景延长 lease 到 {@code deadlineMs}（绝对毫秒时间）。 */
    public void retainLeaseUntil(long deadlineMs) {
        synchronized (lock) {
            this.leaseDeadlineMs = deadlineMs;
            DataPlaneClientBundle b = this.bundle;
            if (b != null) {
                try { b.retainLeaseUntil(deadlineMs); } catch (Throwable ignored) {}
            }
        }
    }
}
