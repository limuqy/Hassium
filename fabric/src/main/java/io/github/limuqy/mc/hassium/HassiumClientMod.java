package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ClientMod");
    private static final FabricNetworkManager networkManager = new FabricNetworkManager();

    @Override
    public void onInitializeClient() {
        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 注册客户端网络通道
        networkManager.registerClientChannels();

        // 监听客户端加入服务器事件，发送握手请求
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
                LOGGER.debug("Hassium: Client joined server, network disabled — skip handshake");
                return;
            }
            networkManager.sendHandshakeRequest();
        });

        // 监听客户端断开连接事件，关闭缓存存储并重置状态
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            var storage = ClientChunkHandler.getClientStorage();
            if (storage != null) {
                storage.close();
            }
            ClientChunkHandler.resetStorage();
            // 立即清空 pending hash/delta，避免 Mixin onDisconnect 触发前的
            // tick 窗口期被 tickPendingHashGate 触发向已关闭连接发包
            ClientMetadataHandler.clearPendingState();
            LOGGER.info("Hassium: Client disconnected, cache cleaned up");
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();

        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
}
