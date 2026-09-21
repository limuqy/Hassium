package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;

/**
 * 服务端网络面门控（握手 / Play 激活 / 区块推送）。
 * <ul>
 *   <li>专用服：{@code master.enabled}</li>
 *   <li>集成服 + 已开局域网（{@code isPublished()}）：{@code master.enabledOnLan}，
 *       仅对远程连接生效</li>
 *   <li>本机 memory 连接：恒不启用（主机玩家走原版推送；客户端缓存看 client.toml）</li>
 *   <li>主世界 type-126 存储：仍仅专用服（见 {@code isStorageEnabled()}）</li>
 * </ul>
 */
public final class ServerNetworkGate {

    private ServerNetworkGate() {
    }

    /** 本进程是否应跑服务端网络面 tick / 激活泵（与 master 开关组合后的总门）。 */
    public static boolean isNetworkServerActive() {
        if (RuntimeServerContext.isDedicatedServerContext()) {
            return HassiumConfigService.getInstance().isMasterEnabled();
        }
        return isLanNetworkActive();
    }

    /** 集成服 LAN 网络面：已发布到局域网且 {@code master.enabledOnLan} 开。 */
    public static boolean isLanNetworkActive() {
        if (RuntimeServerContext.isDedicatedServerContext()) {
            return false;
        }
        MinecraftServer server = RuntimeServerContext.getActiveServer();
        if (server == null || !server.isPublished()) {
            return false;
        }
        return HassiumConfigService.getInstance().isMasterEnabledOnLan();
    }

    /**
     * 该连接是否启用 Hassium 网络面。
     * memory 连接（主机本机玩家）始终 false；其余看 {@link #isNetworkServerActive()}。
     */
    public static boolean shouldUseForConnection(Connection connection) {
        if (isMemoryConnection(connection)) {
            return false;
        }
        return isNetworkServerActive();
    }

    /** 1.20.1 login query 是否发送（无 connection 时按非 memory 保守处理）。 */
    public static boolean shouldSendLoginHandshake(Connection connection) {
        return shouldUseForConnection(connection);
    }

    public static boolean isMemoryConnection(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            return connection.isMemoryConnection();
        } catch (Throwable t) {
            // 反射/映射异常时保守视为非 memory：专用服路径不受影响
            return false;
        }
    }

    /**
     * {@code ServerPlayer} 物化后是否跳过 Hassium 推送抑制。
     * 仅本机 memory 玩家跳过；远程 LAN/专用服玩家走正常协商消费与激活。
     * （注意：不能按 {@link #shouldUseForConnection} 取反实现——网络面未激活时
     * 远程玩家同样应消费协商位走激活路径，见 {@code ServerHandshakeActivation.onPlayerInit}。）
     */
    public static boolean shouldSkipForPlayer(net.minecraft.server.level.ServerPlayer player) {
        if (player == null) {
            return true;
        }
        return isMemoryConnection(io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player));
    }

    /**
     * 该玩家的原版整柱下发是否接受 {@code master.maxChunksPerTick} 限速。
     * <ul>
     *   <li>影子端：否（影子区块只经 Compare+Pull / 影子交付链回传）</li>
     *   <li>专用服：是（含尚未握手的原版客户端；与自有 Pull 通道同频）</li>
     *   <li>集成服已开局域网：仅远程玩家（主机 memory 连接保持原版速度）</li>
     * </ul>
     * 与 {@code master.enabled} / 握手状态无关：限速是服务端出口保护，
     * 不得因 Hassium 未启用而让原版客户端打满带宽。
     */
    public static boolean shouldRateLimitChunkSend(net.minecraft.server.level.ServerPlayer player) {
        if (RuntimeServerContext.isShadowServerContext()) {
            return false;
        }
        if (RuntimeServerContext.isDedicatedServerContext()) {
            return true;
        }
        MinecraftServer server = RuntimeServerContext.getActiveServer();
        if (server == null || !server.isPublished()) {
            return false;
        }
        return !isMemoryConnection(io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player));
    }
}
