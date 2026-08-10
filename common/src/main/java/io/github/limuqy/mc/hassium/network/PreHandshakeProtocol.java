package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * 预握手协议（login / 配置阶段，早于 Play 完整握手）。
 * <p>
 * 目的：让服务端在 {@code ServerPlayer} 创建前就知道客户端是 Hassium，
 * {@link PlayerCompressionTracker#tryEnableOnPlayerJoin} 在进服第一圈
 * trackChunk/sendChunk 前启用压缩 → 初始区块 100% 走 Hassium 链
 * （剥光 + maxChunksPerTick 限流 + chunkHash 元数据），消灭握手前原版直发窗口。
 * <p>
 * 载体分三段：
 * <ul>
 *   <li>1.20.1 fabric：原版 login CustomQuery（服务端 query → 客户端回复）</li>
 *   <li>1.20.2-1.20.4 fabric：配置阶段 Identifier 通道（{@code ClientConfigurationNetworking} legacy）</li>
 *   <li>1.20.2+（neoforge 1.20.5+ / forge 1.20.6 / fabric 1.20.5+）：{@link PreHandshakePayload}
 *       （{@code CustomPacketPayload}，1.20.2+ 配置阶段协议原生支持）</li>
 * </ul>
 * 各载体共享同一字段布局：{@link #encodeFields} / {@link #decodeFields}。
 * <p>
 * 预握手只标记「Hassium 客户端」；位置、ZSTD 协商、聚合、数据面仍走 Play 完整握手
 * （幂等：重复 enableCompression 无害）。
 */
public final class PreHandshakeProtocol {

    private PreHandshakeProtocol() {
    }

    /**
     * 写预握手能力字段（1.20.1 login query 回复 / 1.20.2-1.20.4 fabric legacy 共用）。
     * 与 {@link PreHandshakePayload#STREAM_CODEC} 字段布局一致。
     */
    public static void encodeFields(FriendlyByteBuf buf) {
        buf.writeVarInt(Constants.CURRENT_PROTOCOL_VERSION);
        buf.writeUtf(Constants.MOD_VERSION);
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        buf.writeBoolean(cfg.isClientCacheEnabled());
        buf.writeBoolean(cfg.isGlobalPacketCompressionEnabled());
        buf.writeBoolean(cfg.isCompactHeaderEnabled());
    }

    /**
     * 读预握手能力字段并标记该玩家。
     *
     * @param playerId 目标玩家 UUID（login/配置阶段无 ServerPlayer，按 UUID 标记）
     * @param buf      客户端回复；空 buf（vanilla 客户端 unknowing）时仅记日志不标记
     */
    public static void handlePreHandshake(UUID playerId, FriendlyByteBuf buf) {
        if (playerId == null || buf == null || !buf.isReadable()) {
            DebugLogger.info(LogType.NETWORK,
                    "[PRE_HANDSHAKE] Ignored empty/unknown pre-handshake (player={})", playerId);
            return;
        }
        try {
            int protocolVersion = buf.readVarInt();
            String modVersion = buf.readUtf(128);
            boolean clientCache = buf.readBoolean();
            boolean globalCompression = buf.readBoolean();
            boolean compactHeader = buf.readBoolean();
            // review-fix: T2-72: 网络可控声明在标记前校验（范围 + mod 格式）；失败仅记日志不标记，
            // 防止任意客户端自声明 Hassium 而服务端进服即启用压缩/聚合语义
            if (!validateAndLog(playerId, protocolVersion, modVersion)) {
                return;
            }
            PlayerCompressionTracker.markPreHandshake(playerId);
            DebugLogger.info(LogType.NETWORK,
                    "[PRE_HANDSHAKE] Marked {} (protocol={}, mod={}, clientCache={}, globalCompression={}, compactHeader={})",
                    playerId, protocolVersion, modVersion, clientCache, globalCompression, compactHeader);
        } catch (Exception e) {
            DebugLogger.warn(LogType.NETWORK,
                    "[PRE_HANDSHAKE] Failed to decode pre-handshake from {}: {}", playerId, e.toString());
        }
    }

    /**
     * 校验客户端自声明的协议版本与 mod 版本；失败仅记日志（不标记）。
     * <p>
     * review-fix: T2-72: 协议版本须落在 {@code [1, CURRENT_PROTOCOL_VERSION]}（允许更旧客户端），
     * mod 版本须为常规版本串格式（非空、长度 ≤64、仅 {@code [0-9A-Za-z._+-]}）。
     */
    private static boolean validateAndLog(UUID playerId, int protocolVersion, String modVersion) {
        if (protocolVersion < 1 || protocolVersion > Constants.CURRENT_PROTOCOL_VERSION) {
            DebugLogger.warn(LogType.NETWORK,
                    "[PRE_HANDSHAKE] Rejected protocol version {} (expected 1..{}) from player {}, not marking",
                    protocolVersion, Constants.CURRENT_PROTOCOL_VERSION, playerId);
            return false;
        }
        if (!isValidModVersion(modVersion)) {
            DebugLogger.warn(LogType.NETWORK,
                    "[PRE_HANDSHAKE] Rejected malformed mod version '{}' from player {}, not marking",
                    sanitizeLog(modVersion), playerId);
            return false;
        }
        return true;
    }

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

    /**
     * 过滤日志输出中的控制字符（防日志注入；失败的 mod 版本可能携带任意字符）。
     */
    private static String sanitizeLog(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                if (sb == null) {
                    sb = new StringBuilder(value.length());
                    sb.append(value, 0, i);
                }
                sb.append('?');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : value;
    }

#if MC_VER >= MC_1_20_5
    /**
     * 配置阶段 payload 版入口（fabric 1.20.5+ / neoforge / forge）。
     */
    public static void handlePreHandshake(UUID playerId, PreHandshakePayload payload) {
        if (playerId == null || payload == null) {
            DebugLogger.info(LogType.NETWORK, "[PRE_HANDSHAKE] Ignored null pre-handshake (player={})", playerId);
            return;
        }
        // review-fix: T2-72: 配置阶段入口同样先校验（网络可控声明）再标记
        if (!validateAndLog(playerId, payload.protocolVersion(), payload.modVersion())) {
            return;
        }
        PlayerCompressionTracker.markPreHandshake(playerId);
        DebugLogger.info(LogType.NETWORK,
                "[PRE_HANDSHAKE] Marked {} (protocol={}, mod={}, clientCache={}, globalCompression={}, compactHeader={})",
                playerId, payload.protocolVersion(), payload.modVersion(), payload.clientCache(),
                payload.globalCompression(), payload.compactHeader());
    }
#endif
}
