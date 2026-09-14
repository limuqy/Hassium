package io.github.limuqy.mc.hassium.network.handshake;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.ReflectionCompat;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 登录期能力握手——服务端协商核心。
 * <p>
 * 统一两态（1.20.1 login query）：
 * <ul>
 *   <li><b>原版</b>：空应答 / 解码失败 / 超时 → 不抑制推送</li>
 *   <li><b>Hassium</b>：有效能力位 → {@code markNegotiatedCaps}，物化时压原版并走 Pull</li>
 * </ul>
 * 等待应答期间<b>不发 GameProfile</b>（不 placeNewPlayer）→ 握手前不推任何区块。
 * 1.20.2+ 配置阶段 payload 直带 UUID（{@code getOwner()}）。
 */
public final class LoginHandshakeManager {

    /** 已发 query 待应答（Weak：连接断开自动失效）。 */
    private static final Map<Connection, Integer> PENDING_QUERY_CAPS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 应答已落定（原版或 Hassium）；与 PENDING 互斥。 */
    private static final java.util.Set<Connection> ANSWER_RESOLVED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private LoginHandshakeManager() {
    }

    /**
     * 登记待应答 query（1.20.1 服务端 mixin 发送后调用）。
     */
    public static void onQuerySent(Connection connection, int serverCaps) {
        PENDING_QUERY_CAPS.put(connection, serverCaps);
        ANSWER_RESOLVED.remove(connection);
    }

    /** query 已发出且应答尚未落定。 */
    public static boolean isAwaitingAnswer(Connection connection) {
        return connection != null && PENDING_QUERY_CAPS.containsKey(connection)
                && !ANSWER_RESOLVED.contains(connection);
    }

    /** 应答已落定（可继续 GameProfile / placeNewPlayer）。 */
    public static boolean isAnswerResolved(Connection connection) {
        return connection != null && ANSWER_RESOLVED.contains(connection);
    }

    /** 超时兜底：视为原版客户端，放行登录。 */
    public static void markAnswerResolvedVanilla(Connection connection, String reason) {
        if (connection == null) {
            return;
        }
        PENDING_QUERY_CAPS.remove(connection);
        ANSWER_RESOLVED.add(connection);
        Constants.LOG.info("[LOGIN_HELLO] Resolved as vanilla ({})", reason);
    }

    private static void markResolved(Connection connection) {
        if (connection != null) {
            PENDING_QUERY_CAPS.remove(connection);
            ANSWER_RESOLVED.add(connection);
        }
    }

    /**
     * 处理 1.20.1 login query 应答（{@code ServerboundCustomQueryPacket.getData()}）。
     * 空体 = vanilla 客户端（原版路径）；解析/校验失败 = 原版路径（不标记）。
     *
     * @param loginListener 服务端 login listener（mixin 目标实例，用于反射取 GameProfile）
     * @return true = 本应答属于 Hassium query（调用方应跳过 vanilla 后续处理）
     */
    public static boolean handleAnswer(Object loginListener, Connection connection, FriendlyByteBuf buf) {
        Integer serverCaps = PENDING_QUERY_CAPS.remove(connection);
        if (serverCaps == null) {
            return false;
        }
        if (buf == null || !buf.isReadable()) {
            DebugLogger.info(LogType.NETWORK, "[LOGIN_HELLO] Empty answer (vanilla client)");
            markResolved(connection);
            return true;
        }
        try {
            UUID playerId = resolveAnswerPlayerId(loginListener, connection);
            if (playerId == null) {
                DebugLogger.warn(LogType.NETWORK,
                        "[LOGIN_HELLO] Cannot resolve player id at answer time, vanilla path");
                markResolved(connection);
                return true;
            }
            LoginHandshake.HelloAnswer answer = LoginHandshake.HelloAnswer.decode(buf);
            if (!LoginHandshake.isProtocolVersionAccepted(answer.protocolVersion(),
                    Constants.CURRENT_PROTOCOL_VERSION)) {
                DebugLogger.info(LogType.NETWORK,
                        "[LOGIN_HELLO] Protocol {} from {} not accepted (need {}), vanilla path",
                        answer.protocolVersion(), playerId, Constants.CURRENT_PROTOCOL_VERSION);
                markResolved(connection);
                return true;
            }
            if (!isValidModVersion(answer.modVersion())) {
                DebugLogger.warn(LogType.NETWORK,
                        "[LOGIN_HELLO] Rejected malformed mod version from {}, vanilla path", playerId);
                markResolved(connection);
                return true;
            }
            int negotiated = LoginCaps.negotiate(serverCaps, answer.clientCaps());
            if (negotiated == 0) {
                DebugLogger.info(LogType.NETWORK,
                        "[LOGIN_HELLO] No common caps for {}, vanilla path", playerId);
                markResolved(connection);
                return true;
            }
            PlayerCompressionTracker.markNegotiatedCaps(playerId, negotiated);
            markResolved(connection);
            DebugLogger.info(LogType.NETWORK, "[LOGIN_HELLO] Negotiated {} for {}",
                    LoginHandshake.describeCaps(negotiated), playerId);
        } catch (Exception e) {
            DebugLogger.warn(LogType.NETWORK, "[LOGIN_HELLO] Failed to decode answer: {}", e.toString());
            markResolved(connection);
        }
        return true;
    }

    /**
     * Connection 层提前消费 1.20.1 login query 应答（无论当前 listener 是 Login 还是已切 Game）。
     * <p>
     * 应答可能在 {@code GameProfile} 后到达——此时 listener 已是
     * {@code ServerGamePacketListenerImpl}，原版 {@code handleCustomQueryPacket} 要求
     * {@code ServerLoginPacketListener} 会 ClassCastException 并踢线（首次连局域网偶发）。
     * 在 channelRead0 HEAD 消费并 cancel，原版不再分发。
     *
     * @return true = 已消费（属于本模组 query）
     */
    public static boolean consumeQueryAnswerAtChannel(Connection connection, FriendlyByteBuf buf) {
        Integer serverCaps = PENDING_QUERY_CAPS.get(connection);
        if (serverCaps == null) {
            return false;
        }
        return handleAnswer(resolveListenerForConnection(connection), connection, buf);
    }

    private static Object resolveListenerForConnection(Connection connection) {
        if (connection == null) {
            return null;
        }
        return connection.getPacketListener();
    }

    /** 应答时解析玩家 UUID：优先 login listener 的 GameProfile；已切 Play 时从 ServerPlayer 取。 */
    private static UUID resolveAnswerPlayerId(Object loginListener, Connection connection) {
        UUID fromLogin = resolveLoginPlayerId(loginListener);
        if (fromLogin != null) {
            return fromLogin;
        }
        try {
            if (connection != null
                    && connection.getPacketListener() instanceof net.minecraft.server.network.ServerGamePacketListenerImpl game) {
                net.minecraft.server.level.ServerPlayer player = game.getPlayer();
                if (player != null) {
                    return player.getUUID();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 1.20.2+ 配置阶段载体登记（loader receiver 调用；UUID 由 listener owner 提供）。
     */
    public static void markNegotiated(UUID playerId, int negotiatedCaps) {
        if (playerId == null || negotiatedCaps == 0) {
            return;
        }
        PlayerCompressionTracker.markNegotiatedCaps(playerId, negotiatedCaps);
        DebugLogger.info(LogType.NETWORK, "[LOGIN_HANDSHAKE] Negotiated {} for {}",
                LoginHandshake.describeCaps(negotiatedCaps), playerId);
    }

    /** 连接断开清理（MixinConnection disconnect 清理点转调）。 */
    public static void onDisconnect(Connection connection) {
        PENDING_QUERY_CAPS.remove(connection);
        ANSWER_RESOLVED.remove(connection);
    }

    /**
     * 按 listener 实例解析玩家 UUID（原 {@code FabricNetworkManager.resolveLoginPlayerId}
     * 收口）：按类型反射取 listener 的 GameProfile 字段（SRG/intermediary 名免疫）。
     * fabric 查询应答处理时认证线程已完成（在线服 profile 带认证 UUID）；离线服
     * profile id 为空，按原版 {@code createFakeProfile} 同款派生 OfflinePlayer UUID。
     */
    public static UUID resolveLoginPlayerId(Object loginListener) {
        try {
            com.mojang.authlib.GameProfile profile = (com.mojang.authlib.GameProfile)
                    ReflectionCompat.getFieldByType(loginListener, com.mojang.authlib.GameProfile.class, true);
            if (profile == null) {
                return null;
            }
            UUID id = PlayerCompat.getProfileId(profile);
            if (id != null) {
                return id;
            }
            String name = PlayerCompat.getProfileName(profile);
            if (name != null) {
                return net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name);
            }
        } catch (Exception e) {
            DebugLogger.warn(LogType.NETWORK, "[LOGIN_HELLO] Failed to resolve login player id: {}", e.toString());
        }
        return null;
    }

    /**
     * mod 版本格式校验（T2-72 同款：非空、长度 ≤64、仅 {@code [0-9A-Za-z._+-]}），防日志注入。
     */
    private static boolean isValidModVersion(String version) {
        if (version == null || version.isEmpty() || version.length() > 64) {
            return false;
        }
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == '_' || c == '-' || c == '+';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
