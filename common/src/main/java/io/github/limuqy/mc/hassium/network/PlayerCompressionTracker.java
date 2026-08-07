package io.github.limuqy.mc.hassium.network;

import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪玩家的 Hassium 压缩支持状态
 */
public class PlayerCompressionTracker {

    private static final Map<UUID, Boolean> compressionEnabled = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> connectedAt = new ConcurrentHashMap<>();

    /**
     * 已在 login / 配置阶段完成预握手的玩家（login 线程 / 配置阶段写入，
     * {@code ServerPlayer} 创建时在主线程消费提升）。与 Play 阶段完整握手解耦：
     * 预握手只证明「Hassium 客户端」，ZSTD/聚合/数据面协商仍在 Play 完整握手完成。
     */
    private static final java.util.Set<UUID> preHandshakeDone = ConcurrentHashMap.newKeySet();

    /**
     * 握手超时时间（毫秒）
     */
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000;

    /**
     * 记录玩家连接时间
     */
    public static void setConnected(ServerPlayer player) {
        connectedAt.put(player.getUUID(), System.currentTimeMillis());
    }

    /**
     * 启用玩家的压缩功能
     */
    public static void enableCompression(ServerPlayer player) {
        compressionEnabled.put(player.getUUID(), true);
    }

    /**
     * 检查玩家是否支持压缩
     */
    public static boolean isCompressionEnabled(ServerPlayer player) {
        return compressionEnabled.getOrDefault(player.getUUID(), false);
    }

    /**
     * 记录 login / 配置阶段预握手完成（线程安全；login/配置阶段线程调用）。
     */
    public static void markPreHandshake(UUID playerId) {
        if (playerId != null) {
            preHandshakeDone.add(playerId);
        }
    }

    /**
     * {@code ServerPlayer} 创建时（placeNewPlayer，主线程）调用：
     * 预握手已完成的玩家立即启用压缩，使进服第一圈 trackChunk/sendChunk
     * 全部走 Hassium 链（剥光 + 限流 + hash 元数据），消除握手前原版直发窗口。
     */
    public static void tryEnableOnPlayerJoin(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (preHandshakeDone.remove(playerId)) {
            compressionEnabled.put(playerId, true);
        }
    }

    /**
     * 检查玩家握手是否超时
     *
     * @return true 表示已超时（未在规定时间内完成握手）
     */
    public static boolean isHandshakeTimeout(ServerPlayer player) {
        Long connectTime = connectedAt.get(player.getUUID());
        if (connectTime == null) return false;
        return System.currentTimeMillis() - connectTime > HANDSHAKE_TIMEOUT_MS;
    }

    /**
     * 移除玩家的压缩状态（断开连接时）
     */
    public static void removePlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        compressionEnabled.remove(playerId);
        connectedAt.remove(playerId);
        preHandshakeDone.remove(playerId);
    }

    /**
     * 清空所有玩家状态
     */
    public static void clear() {
        compressionEnabled.clear();
        connectedAt.clear();
        preHandshakeDone.clear();
    }
}
