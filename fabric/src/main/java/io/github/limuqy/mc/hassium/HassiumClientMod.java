package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ClientMod");
    private static final FabricNetworkManager networkManager = new FabricNetworkManager();

    @Override
    public void onInitializeClient() {
        ClientSmokeTest.initIfEnabled();

        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 注册客户端网络通道
        networkManager.registerClientChannels();

        // 监听客户端加入服务器事件：仅发握手；数据面由 HandshakeS2C 携 token/endpoints 触发
        // （DataPlaneClientLifecycle.startFromHandshake 在握手响应里执行，二不在 JOIN 立即连接）
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
                LOGGER.debug("Hassium: Client joined server, network disabled — skip handshake");
                return;
            }
            networkManager.sendHandshakeRequest();
        });

        // 监听客户端断开连接事件，统一走 ClientLifecycleHelper.cleanupOnDisconnect
        // （MixinClientCommonPacketListenerImpl.onDisconnect 在 1.20.2+ 可能不被触发，
        //  必须在此主动清理，否则 initialized 标志残留导致重连后 onLogin early-return）
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientLifecycleHelper.cleanupOnDisconnect();
            // 关闭多通道数据面（由握手建立的 bundle）
            DataPlaneClientLifecycle.stop();
            // 延后到下一 tick：等 Minecraft.disconnect / clearLevel 拆除完成；与 Mixin TAIL 幂等
            client.execute(ClientLifecycleHelper::finalizeDisconnect);
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();

        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
}
