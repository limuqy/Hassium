package io.github.limuqy.mc.hassium.protocol;

import net.minecraft.network.Connection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 追踪支持 Hassium 压缩的连接状态。
 * <p>
 * 服务端连接经历两个状态：
 * 1. PENDING - 握手响应发送后，包被缓冲但不刷新
 * 2. ENABLED - 客户端确认后，缓冲的包被刷新，开始正常聚合
 * <p>
 * 客户端：收到 IndexSync 后直接标记为 ENABLED
 * <p>
 * 使用 WeakHashMap 自动清理断开的连接
 */
public class HassiumConnectionRegistry {
    private static final Set<Connection> ENABLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Connection> PENDING =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /**
     * 已协商字典 epoch 帧的连接（aggregation_ready 携带字典回执）。
     * 服务端编码只对这类连接在 DICT 帧头写 epoch；旧协议客户端帧格式不变。
     */
    private static final Set<Connection> EPOCH_AWARE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * 标记连接为待定状态 - 包将被缓冲但不刷新
     * 服务端发送 IndexSync 后调用
     */
    public static void markPending(Connection connection) {
        PENDING.add(connection);
    }

    /**
     * 将连接从待定提升为完全启用
     * 收到客户端 aggregation_ready 时调用
     */
    public static void markEnabled(Connection connection) {
        synchronized (PENDING) {
            PENDING.remove(connection);
            ENABLED.add(connection);
        }
    }

    /**
     * 禁用连接
     */
    public static void markDisabled(Connection connection) {
        PENDING.remove(connection);
        ENABLED.remove(connection);
        EPOCH_AWARE.remove(connection);
    }

    /**
     * 连接完全激活 - 允许刷新
     */
    public static boolean isEnabled(Connection connection) {
        return ENABLED.contains(connection);
    }

    /**
     * 连接待定 - 缓冲包但不刷新
     */
    public static boolean isPending(Connection connection) {
        return PENDING.contains(connection);
    }

    /**
     * 连接应该拦截和缓冲包（待定或启用）
     */
    public static boolean isActive(Connection connection) {
        synchronized (PENDING) {
            return ENABLED.contains(connection) || PENDING.contains(connection);
        }
    }

    /**
     * 标记连接已协商字典 epoch 帧（aggregation_ready 携带字典回执时调用；
     * 必须先于 markEnabled/flush，保证缓冲帧冲出时帧头即带 epoch）。
     */
    public static void markEpochAware(Connection connection) {
        EPOCH_AWARE.add(connection);
    }

    /** 连接是否已协商字典 epoch 帧（编码端决定 DICT 帧头是否写 epoch）。 */
    public static boolean isEpochAware(Connection connection) {
        return EPOCH_AWARE.contains(connection);
    }

    /**
     * 当前活跃（ENABLED ∪ PENDING）连接快照。
     * <p>
     * 字典 rollout 门控遍历用（{@code DictionaryManager.evaluateFlip}）；
     * 返回副本，遍历不受后续并发变更影响。
     */
    public static List<Connection> activeConnections() {
        synchronized (PENDING) {
            synchronized (ENABLED) {
                List<Connection> out = new ArrayList<>(ENABLED.size() + PENDING.size());
                out.addAll(ENABLED);
                out.addAll(PENDING);
                return out;
            }
        }
    }

    /**
     * 处于待定或启用状态的连接数（诊断用）。
     * <p>
     * 实体降帧引擎已不再依赖本值：实体域是 vanilla 兼容的复制节拍优化，跟 master 总闸即可，
     * 不要求存在已协商的 Hassium 客户端。
     */
    public static int activeCount() {
        synchronized (PENDING) {
            synchronized (ENABLED) {
                return ENABLED.size() + PENDING.size();
            }
        }
    }

    /**
     * 原子地将待定连接降级为禁用
     * 返回 true 如果连接确实是待定状态
     */
    public static boolean tryDemoteFromPending(Connection connection) {
        synchronized (PENDING) {
            if (PENDING.remove(connection)) {
                ENABLED.remove(connection);
                return true;
            }
            return false;
        }
    }
}
