package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshakeManager;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;

import java.util.UUID;

/**
 * 预握手协议入口（登录期能力协商，早于 ServerPlayer 创建）。
 * <p>
 * 双载体收敛于此（能力位语义统一为 {@link LoginCaps}）：
 * <ul>
 *   <li>1.20.1：vanilla login custom query（{@code hassium:login_hello}），见
 *       {@code MixinServerLoginPacketListenerImpl} → {@link LoginHandshakeManager}；</li>
 *   <li>1.20.2+：配置阶段 {@link PreHandshakePayload}（C2S，loader 注册，
 *       认证已完成可取 UUID）。</li>
 * </ul>
 * 旧 Play 阶段握手轮次（handshake_c2s/handshake_s2c）已裁剪：协商结果经 Play 期
 * {@code play_init_s2c} 下发，激活链见 {@code ServerHandshakeActivation}。
 */
public final class PreHandshakeProtocol {

    private PreHandshakeProtocol() {
    }

#if MC_VER >= MC_1_21_1
    /**
     * 配置阶段 payload 入口（fabric 1.20.5+ / forge / neoforge）。
     * 校验（协议版本范围 + mod 版本格式，防日志注入）通过后按位协商并登记。
     */
    public static void handlePreHandshake(UUID playerId, PreHandshakePayload payload) {
        if (playerId == null || payload == null) {
            DebugLogger.info(LogType.NETWORK,
                    "[PRE_HANDSHAKE] Ignored null pre-handshake (player={})", playerId);
            return;
        }
        if (!LoginHandshake.isProtocolVersionAccepted(payload.protocolVersion(),
                Constants.CURRENT_PROTOCOL_VERSION)) {
            DebugLogger.warn(LogType.NETWORK,
                    "[PRE_HANDSHAKE] Rejected protocol version {} (expected 1..{}) from player {}, not marking",
                    payload.protocolVersion(), Constants.CURRENT_PROTOCOL_VERSION, playerId);
            return;
        }
        if (!LoginHandshake.isValidModVersion(payload.modVersion())) {
            DebugLogger.warn(LogType.NETWORK,
                    "[PRE_HANDSHAKE] Rejected malformed mod version from player {}, not marking", playerId);
            return;
        }
        int negotiated = LoginCaps.negotiate(LoginCaps.buildServerCaps(), payload.clientCaps());
        if (negotiated == 0) {
            DebugLogger.info(LogType.NETWORK,
                    "[PRE_HANDSHAKE] No common caps for {}, vanilla path", playerId);
            return;
        }
        LoginHandshakeManager.markNegotiated(playerId, negotiated);
        // 协商成功为一次性冷路径，走 Constants.LOG（无条件）而非 DebugLogger：
        // 冒烟门禁与排障依赖此行区分「handler 未被调用 vs 调用了但没日志」。
        Constants.LOG.info("[PRE_HANDSHAKE] Negotiated {} for {}",
                LoginHandshake.describeCaps(negotiated), playerId);
    }
#endif
}
