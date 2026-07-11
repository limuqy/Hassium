package io.github.limuqy.mc.hassium.network;

import net.minecraft.network.Connection;

import java.util.Collections;
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
     * 标记连接为待定状态 - 包将被缓冲但不刷新
     * 服务端发送 IndexSync 后调用
     */
    public static void markPending(Connection connection) {
        PENDING.add(connection);
    }

    /**
     * 将连接从待定提升为完全启用
     * 收到客户端 CompressionReady 时调用
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
