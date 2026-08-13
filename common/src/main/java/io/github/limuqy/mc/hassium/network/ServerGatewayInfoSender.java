package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端 gateway_info 下发（M1 bootstrap，CONTRACTS §2）。
 * <p>
 * 玩家物化（{@code ServerPlayer} &lt;init&gt; TAIL，MixinServerPlayer）时登记待发集合，
 * 由 MixinMinecraftServer 每 tick 泵（{@link #drainPending}）在玩家物化
 * （connection 挂载、进入玩家表）后统一发送并移除——单发送者天然去重，每次 join 只发一次；
 * 泵机制同时防 &lt;init&gt; 时 connection 未挂的竞态。
 * <p>
 * 仅专用服 + {@code master.enabled}（客户端进程内的影子服不参与）；普通玩家连接非桥接，
 * 不走网关 S2C 路由（MixinConnectionGatewayServer 只拦桥接连接）。
 */
public final class ServerGatewayInfoSender {

    /** 待 tick 泵补发的玩家 UUID（Set 语义：同一玩家只发一次）。 */
    private static final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();

    private ServerGatewayInfoSender() {
        // 工具类，禁止实例化
    }

    /**
     * 玩家物化完成时调用（ServerPlayer &lt;init&gt; TAIL）：登记待发集合。
     * <p>
     * 仅登记、不发送：统一由 {@link #drainPending}（tickServer TAIL 泵）在玩家物化
     * （connection 挂载、进入玩家表）后发送并移除——单发送者天然去重，每次 join 只发一次。
     * &lt;init&gt; 时 connection 尚未挂载（placeNewPlayer 构造后才赋值），立即发送路径
     * 与泵补发路径会双发，已移除。
     */
    public static void onPlayerInit(ServerPlayer player) {
        if (!canSend()) {
            return;
        }
        pendingPlayers.add(player.getUUID());
    }

    /**
     * 每 tick 泵（tickServer TAIL）——gateway_info 唯一发送点：
     * 待发玩家物化（connection 挂载、进入玩家表）后发送并移除；玩家已消失则 evict 防泄漏。
     */
    public static void drainPending(MinecraftServer server) {
        if (!canSend() || pendingPlayers.isEmpty()) {
            return;
        }
        for (UUID playerId : pendingPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                // 物化失败/join 中断（如 <init> 后下一 tick 前断连）：放弃补发，防集合泄漏
                pendingPlayers.remove(playerId);
                continue;
            }
            if (player.connection != null) {
                sendTo(player);
                pendingPlayers.remove(playerId);
            }
        }
    }
    /** 仅专用服 + 网关主控开关时下发（客户端进程内影子服/单机不参与）。 */
    private static boolean canSend() {
        return RuntimeServerContext.isDedicatedServerContext()
                && HassiumConfigService.getInstance().isMasterEnabled();
    }

    private static void sendTo(ServerPlayer player) {
        HassiumConfigService config = HassiumConfigService.getInstance();
        List<HassiumConfig.ReachableEndpoint> configured = config.getControlReachableEndpoints();
        List<GatewayInfoCodec.Endpoint> endpoints = new ArrayList<>(configured.size());
        for (HassiumConfig.ReachableEndpoint ep : configured) {
            endpoints.add(new GatewayInfoCodec.Endpoint(ep.host(), ep.port(), ep.priority()));
        }
        GatewayInfoCodec.GatewayInfo info = new GatewayInfoCodec.GatewayInfo(
                Constants.CURRENT_PROTOCOL_VERSION,
                Constants.MOD_VERSION,
                endpoints,
                config.getMasterAuthToken(),
                config.isNetworkCompressionEnabled(),
                config.isSeedGenEnabled(),
                config.isHassiumEngineEnabled());
        byte[] data = GatewayInfoCodec.encode(info);
        player.connection.send(PacketPayloadCompat.createClientboundPayload(
                ResourceLocationCompat.create(HassiumPacketIds.GATEWAY_INFO_S2C), data));
        DebugLogger.info(LogType.NETWORK, "Hassium: gateway_info sent to {} (endpoints={}, auth={})",
                player.getName().getString(), endpoints.size(),
                config.getMasterAuthToken().isEmpty() ? "none" : "set");
    }
}
