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
     * 登录期能力协商结果（UUID → 协商能力位；{@code ServerPlayer} 创建时消费）。
     */
    private static final Map<UUID, Integer> negotiatedCaps = new ConcurrentHashMap<>();

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
     * 玩家物化时消费 login/config 预握手。此时客户端已明确安装 Hassium，
     * 因此首批 vanilla tracking 区块必须交给 ServerChunkPushManager，不能直接下发。
     */
    public static void tryEnableOnPlayerJoin(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        if (preHandshakeDone.remove(id)) {
            compressionEnabled.put(id, true);
        }
    }

    /**
     * 登记登录期能力协商结果（握手阶段线程安全写入；物化时消费）。
     */
    public static void markNegotiatedCaps(UUID playerId, int caps) {
        if (playerId != null) {
            negotiatedCaps.put(playerId, caps);
        }
    }

    /**
     * 物化时消费协商位（消费即移除；无登记返回 0）。
     */
    public static int consumeNegotiatedCaps(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        Integer caps = negotiatedCaps.remove(player.getUUID());
        return caps != null ? caps : 0;
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
        negotiatedCaps.remove(playerId);
    }

    /**
     * 清空所有玩家状态
     */
    public static void clear() {
        compressionEnabled.clear();
        connectedAt.clear();
        preHandshakeDone.clear();
        negotiatedCaps.clear();
    }
}
