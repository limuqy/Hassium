package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint;
import io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectOrchestrator;
import io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ClientMod");
    private static final FabricNetworkManager networkManager = new FabricNetworkManager();

    // Task 9 — control-reconnect orchestrator singleton; launcher wires vanilla ConnectScreen。
    // advertised candidates are populated from the S2C handshake tail; bootstrap active comes
    // from the prior live Connection's remote address on disconnect.
    private static volatile ControlReconnectOrchestrator reconnectOrchestrator;

    /** 客户端 access — FabricNetworkManager S2C handler 用以灌候选与 onHandshakeAccepted 回调。 */
    public static ControlReconnectOrchestrator reconnectOrchestrator() {
        return reconnectOrchestrator;
    }

    @Override
    public void onInitializeClient() {
        // Task 9 — control-reconnect orchestrator (singleton; launcher wired here, candidates
        // populated from S2C handshake tails as advertised backup endpoints arrive).
        if (reconnectOrchestrator == null) {
            reconnectOrchestrator = new ControlReconnectOrchestrator(
                    new io.github.limuqy.mc.hassium.client.FabricControlReconnectLauncher(),
                    java.util.List.of(),
                    java.util.List.of());
        }

        ClientSmokeTest.initIfEnabled();

        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 注册客户端网络通道
        networkManager.registerClientChannels();

        // 客户端加入服务器事件：发送握手请求。
        // Task 5 — UDP 数据面 bundle 由 S2C 握手尾部（FabricNetworkManager）驱动，
        // 通过 DataPlaneClientLifecycle.startUdp 在 accepted 响应中自动启动；
        // 此处不再硬编码直连 PoC Data 副端口（旧 PoC connectAndBind() 已移除）。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
                LOGGER.debug("Hassium: Client joined server, network disabled — skip handshake");
                return;
            }
            networkManager.sendHandshakeRequest();
        });

        // 客户端断开事件：先让 orchestrator 把恢复态开起来，然后 cleanup / finalize。
        // 恢复中 finalizeDisconnectIfTerminal 会短路 one-time terminal；磁盘缓存/executor
        // 留待下一候选握手成功 markRecovered；候选耗尽时 orchestrator 自身触发一次 terminal.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            try {
                ControlReconnectOrchestrator orch = reconnectOrchestrator;
                if (orch != null) {
                    ControlEndpoint active = activeControlEndpoint(handler);
                    // active == null 且 orchestrator 无 advertised 候选 → onPrimaryDisconnected 即刻耗尽
                    // → performTerminalFinalization 把 ClientRecoveryState 推入 TERMINAL 一次。
                    orch.onPrimaryDisconnected(active, "channel_inactive");
                    // 把全局 recovery state 切到 RECOVERING，与 orchestrator 内的 recovering 同步。
                    // 若 orchestrator 已 terminal（无候选 + 已 finalize），begin 不再起作用（TERMINAL 单边）。
                    ClientRecoveryState.getInstance().begin(
                            java.lang.System.currentTimeMillis() + 60_000L);
                }
            } catch (Throwable t) {
                LOGGER.warn("Hassium: reconnect orchestrator begin failed", t);
            }

            ClientLifecycleHelper.cleanupOnDisconnect();
            // 关闭 UDP 数据面 bundle；恢复态下用温和关闭（保留磁盘缓存/executor）
            try {
                DataPlaneClientLifecycle.getInstance().stopUdp(
                        ClientRecoveryState.getInstance().isRecovering());
            } catch (Throwable ignored) {}
            // 延后到下一 tick：等 Minecraft.disconnect / clearLevel 拆除完成；与 Mixin TAIL 幂等
            // 但仅在非恢复态下真跑 finalize；恢复态交给 MixinMinecraft TAIL 在 markTerminal 后触发一次
            client.execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal);
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();
        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }

    private static ControlEndpoint activeControlEndpoint(
            net.minecraft.client.multiplayer.ClientPacketListener handler) {
        try {
            if (handler == null) return null;
            net.minecraft.network.Connection conn = handler.getConnection();
            if (conn == null) return null;
            java.net.SocketAddress addr = conn.getRemoteAddress();
            if (addr instanceof java.net.InetSocketAddress isa) {
                return new ControlEndpoint(isa.getHostString(), isa.getPort(), /*priority*/ 0);
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
