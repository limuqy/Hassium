package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.core.NetworkCore;
import io.github.limuqy.mc.hassium.network.core.NetworkCoreState;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.client.Minecraft;

/**
 * 无网关拓扑下的客户端握手发送：进服（Play 阶段）后一次性向服务端上报能力
 * （SeedGen 种子/LevelStem、剥光协商、位置），服务端以 HANDSHAKE_S2C 响应。
 * <p>
 * 网关拓扑（NetworkCore ACTIVE）下客户端不发送——握手由网关帧连接承担，避免双通道竞争。
 */
public final class ClientHandshakeSender {

    private static volatile String lastHandshakePlayerId = null;

    private ClientHandshakeSender() {}

    /** 每会话一次性触发：进服后、网关未活跃时发送；玩家切换（重连）后允许重发。 */
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (NetworkCore.getInstance().state() == NetworkCoreState.ACTIVE) {
            return;
        }
        String playerId = mc.player.getUUID().toString();
        if (playerId.equals(lastHandshakePlayerId)) {
            return;
        }
        lastHandshakePlayerId = playerId;
        send(mc);
    }

    private static void send(Minecraft mc) {
        HassiumConfigService config = HassiumConfigService.getInstance();
        ClientHandshakeRequest request = new ClientHandshakeRequest(
                Constants.CURRENT_PROTOCOL_VERSION,
                Constants.MOD_VERSION,
                new String[] {Constants.NETWORK_COMPRESSION_ALGORITHM,
                        Constants.NETWORK_COMPRESSION_ALGORITHM + "_dict"},
                true,  // clientCacheSupported
                true,  // chunkRevisionSupported
                false, // scheme127Supported（type 126）
                true,  // globalPacketCompressionSupported
                true,  // compactHeaderSupported
                new UdpDataPlaneHandshakeTail.C2STail(config.getDataPlaneConfig().enabled(), false),
                mc.player.getX(),
                mc.player.getZ(),
                config.isClientSeedGenEnabled(),
                config.isHassiumEngineEnabled());
        Services.NETWORK_MANAGER.sendClientHandshake(request);
    }
}