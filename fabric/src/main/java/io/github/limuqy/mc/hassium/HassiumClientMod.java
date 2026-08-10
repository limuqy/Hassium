package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ClientMod");

    @Override
    public void onInitializeClient() {
        ClientSmokeTest.initIfEnabled();

        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 客户端断开事件：清理 + 关闭 UDP 数据面 bundle + 最终清理（幂等）。
        // 客户端 failover 已退役（T6）：无恢复态逻辑，UDP 束直接硬关；
        // 新架构客户端不主动发握手（receiver 已删），数据面由网关 outbound 接管。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientLifecycleHelper.cleanupOnDisconnect();
            try {
                DataPlaneClientLifecycle.getInstance().stopUdp(false);
            } catch (Throwable ignored) {
                // UDP 数据面可选；关闭失败不得阻断断连清理
            }
            // 延后到下一 tick：等 Minecraft.disconnect / clearLevel 拆除完成；与 Mixin TAIL 幂等
            client.execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal);
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();
        // review-fix: T10-11: Fabric 客户端零 S2C receiver（T12 收口，网关为唯一支持客户端形态；客户端 receiver 已退役）。
        // 非网关直连时所有 Hassium S2C 通道被 Fabric 静默丢弃 → 显式告警，便于第一时间定位（不改消费路径）。
        LOGGER.warn("Hassium: Fabric client registers no Hassium S2C receivers — Hassium server->client packets are only consumed via the gateway topology (T12). Direct connections to a Hassium server will silently drop them.");
        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
}
