package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.SeedGenTail;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneHandshakeAdvertisement;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.github.limuqy.mc.hassium.network.gateway.GatewayServer;
import io.github.limuqy.mc.hassium.network.gateway.GatewayServerInfoProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 主控平台接线（T12）：把 GatewayServer 接入 MinecraftServer 生命周期与真实字段。
 *
 * <p><b>接线点</b>（MixinMinecraftServer.onServerInit，dedicated 分支，与
 * DataPlaneUdpServer 同模式）：
 * <ul>
 *   <li>{@link #install}：setInfoProvider（真实握手字段——镜像 vanilla 握手响应链
 *       口径，见 {@link #resolveServerInfo}）/ setLoginSink（LOGIN_C2S 帧 →
 *       {@link GatewayPlayerBridge#dispatchLoginFrame}）/ setZstd（与客户端同源的
 *       globalCompressionThreshold/Level）/ 会话清理钩子 / start（端口 =
 *       controlReachableEndpoints[0] 或 25566 兜底）。bind 失败仅日志（vanilla
 *       TCP 不受影响）。</li>
 *   <li>onServerTick：{@link GatewayPlayerBridge#tick}（登录监听器泵）。</li>
 *   <li>onServerStop：{@link #shutdown}（桥清理 + GatewayServer.stop，幂等）。</li>
 * </ul>
 *
 * <p><b>握手字段口径</b>（镜像三端 completeServerHandshake，帧侧不经过 vanilla
 * 状态机）：protocolVersion=Constants.CURRENT_PROTOCOL_VERSION、accepted=true、
 * globalCompressionAccepted/compactHeaderAccepted=服务端配置（帧握手不携带客户端
 * 压缩能力位——T4 帧格式定稿；客户端 outbound 收到 accepted 即装 ZSTD）、
 * udpTail=DataPlaneHandshakeAdvertisement.create（udpSupported=false：
 * Wave3 网关走 TCP 帧，UDP 数据面 T8；token/epoch=0——帧连接即控制连接）、
 * worldSeed/levelStemNbt=SeedGenTail.encodeLevelStemNbt(overworld)、
 * seedGenEnabled=配置。
 *
 * <p>断连清理：会话移除钩子 → {@link GatewayPlayerBridge#onPlayerSessionRemoved}
 * （PlayerCompressionTracker.removePlayer + PlayerList.remove 完整 vanilla 移除）。
 * ResumeTicketValidator epoch 表不清理（防重放，T7 定稿）。
 */
public final class GatewayPlatformWiring {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/GatewayPlatformWiring");

    private GatewayPlatformWiring() {
    }

    /** 主控启动接线（MixinMinecraftServer.onServerInit，dedicated）。失败不炸主线程。 */
    public static void install(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            GatewayServer gateway = GatewayServer.getInstance();
            gateway.setInfoProvider((channel, request) -> resolveServerInfo(server));
            gateway.setLoginSink((ch, payload) -> GatewayPlayerBridge.dispatchLoginFrame(ch, payload, server));
            // T2-fabric-r1：CHUNK_APPLY_ACK 不再经 server.execute 切主线程。R1 冷生成期
            // 主线程积压会把 ACK 处理拖后数秒，admission 窗口只能靠 8s 超时释放 →
            // fabric 1.21.2+ R1 landed 崩到 neoforge 的 ~60%（实证：server_1.21.6_fabric
            // inFlight 钉死 40、pending 顶满 384、expire 50 次；neoforge 同拓扑全绿）。
            // 本方法仅触达 ConcurrentHashMap 与 synchronized 的 ChunkAdmissionController，
            // 会话身份校验（registry().get != session）已在网关侧挡住重连竞态，可直接在
            // 网关 event-loop 线程处理。
            gateway.setChunkApplyAckSessionSink((session, ack) ->
                    ServerChunkPushManager.getInstance().handleChunkApplyAck(session, ack));
            HassiumConfigService config = HassiumConfigService.getInstance();
            gateway.setZstd(config.getGlobalCompressionThreshold(), config.getGlobalCompressionLevel());
            // D-M2: 可选握手鉴权（master.authToken；空 = 不鉴权，保持既有行为）
            gateway.setAuthToken(config.getMasterAuthToken());
            // per-player 清理：PlayerCompressionTracker + vanilla PlayerList 完整移除
            // （帧连接已关 → S2C 经桥自然丢弃；DataPlaneUdpServer 无 UDP 会话，无级联）
            gateway.registry().addPlayerRemovalHook(
                    playerId -> GatewayPlayerBridge.onPlayerSessionRemoved(server, playerId));
            // 续流会话物化调度（会话登记 → 主线程 muted placeNewPlayer + resyncTrackedChunks）
            gateway.registry().addPlayerAttachHook(
                    session -> GatewayPlayerBridge.materializeResumePlayer(server, session));
            gateway.start(resolveBindHost(config), resolveBindPort(config));
            LOGGER.info("[GATEWAY] platform wiring installed: zstd={}/{} port-source={}",
                    config.getGlobalCompressionThreshold(), config.getGlobalCompressionLevel(),
                    config.getControlReachableEndpoints().isEmpty() ? "default" : "config[0]");
        } catch (Throwable t) {
            // 接线失败不拖垮 vanilla 服务端（T0：网关 = 旁路增强）
            LOGGER.error("[GATEWAY] platform wiring failed — 网关不可用（vanilla 路径不受影响）", t);
        }
    }

    /** 主控停机（MixinMinecraftServer.onServerStop）。幂等。 */
    public static void shutdown(MinecraftServer server) {
        try {
            GatewayPlayerBridge.clearAll();
            GatewayServer.getInstance().stop();
            LOGGER.info("[GATEWAY] platform wiring shut down");
        } catch (Throwable t) {
            LOGGER.warn("[GATEWAY] platform shutdown error", t);
        }
    }

    // ==================== 握手字段 ====================

    /**
     * 镜像 vanilla 握手响应链口径（三端 completeServerHandshake）的网关 S2C 字段。
     * event loop 线程调用：读配置 + 轻量世界信息（SeedGenTail 编码 ~O(stem)）；
     * 失败回落保守默认（acceptDefaults）。
     */
    private static GatewayServerInfoProvider.ServerHandshakeInfo resolveServerInfo(MinecraftServer server) {
        HassiumConfigService config = HassiumConfigService.getInstance();
        UdpDataPlaneHandshakeTail.S2CTail udpTail;
        long seed = 0L;
        byte[] stemNbt = null;
        boolean seedGenEnabled = config.isSeedGenEnabled();
        try {
            ServerLevel overworld = server.overworld();
            seed = SeedGenTail.handshakeWorldSeed(overworld, seedGenEnabled);
            if (seedGenEnabled) {
                stemNbt = SeedGenTail.encodeLevelStemNbt(overworld);
            }
        } catch (Throwable t) {
            LOGGER.warn("[GATEWAY] world seed/stem resolve failed — 回落保守默认", t);
        }
        try {
            // 帧连接即控制连接：不 beginControlConnection（UDP 会话 T8 并入票据 epoch 口径）。
            // D-M1: udpSupported=false 时 token 不参与下发（create() 对 !hasUdp 写零 token），
            // 直接传 null——per-player bind token 仅由三端数据面握手尾经 getBindToken 下发。
            udpTail = DataPlaneHandshakeAdvertisement.create(
                    DataPlaneUdpServer.advertisedControlEndpoints(),
                    DataPlaneUdpServer.boundEndpoints(),
                    null,
                    0L, false, false);
        } catch (Throwable t) {
            LOGGER.warn("[GATEWAY] udp tail resolve failed — disabled", t);
            udpTail = UdpDataPlaneHandshakeTail.S2CTail.disabled();
        }
        return new GatewayServerInfoProvider.ServerHandshakeInfo(
                Constants.CURRENT_PROTOCOL_VERSION,
                true,
                config.isGlobalPacketCompressionEnabled(),
                config.isCompactHeaderEnabled(),
                udpTail,
                seed,
                stemNbt,
                seedGenEnabled);
    }

    // ==================== 监听端口 ====================

    /**
     * T5-M3 修复：网关默认绑定 127.0.0.1 回环，避免无鉴权监听暴露到公网。
     * bind host 唯一来源 = {@code master.bindHost}（默认 127.0.0.1；空串 = 0.0.0.0 全网卡，
     * 生产多网卡显式声明）。不再回退 {@code controlReachableEndpoints[0].host()}——
     * 那是客户端可达地址（可含公网 IP），不等于本机 bind 地址。
     */
    private static String resolveBindHost(HassiumConfigService config) {
        String bindHost = config.getMasterBindHost();
        if (bindHost == null || bindHost.isBlank()) {
            return "0.0.0.0";
        }
        return bindHost;
    }

    private static int resolveBindPort(HassiumConfigService config) {
        List<io.github.limuqy.mc.hassium.config.HassiumConfig.ReachableEndpoint> endpoints =
                config.getControlReachableEndpoints();
        if (!endpoints.isEmpty()) {
            int port = endpoints.get(0).port();
            if (port > 0 && port < 65536) {
                return port;
            }
        }
        LOGGER.warn("[GATEWAY] master.controlReachableEndpoints 未配置有效端口 — 网关监听默认 {}（客户端地址源 = T7/T8 迁移引擎）",
                GatewayPlayerBridge.DEFAULT_GATEWAY_PORT);
        return GatewayPlayerBridge.DEFAULT_GATEWAY_PORT;
    }
}
