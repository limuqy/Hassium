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
        compressionEnabled.remove(player.getUUID());
        connectedAt.remove(player.getUUID());
    }

    /**
     * 清空所有玩家状态
     */
    public static void clear() {
        compressionEnabled.clear();
        connectedAt.clear();
    }
}
