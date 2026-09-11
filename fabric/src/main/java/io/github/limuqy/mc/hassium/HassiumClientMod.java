package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.network.AggregationDecodeQueue;
import io.github.limuqy.mc.hassium.network.ClientActivation;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.PayloadHandlers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
#if MC_VER >= MC_1_21_1
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
#endif
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ClientMod");

    @Override
    public void onInitializeClient() {
        ClientSmokeTest.initIfEnabled();

        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 客户端断开事件：清理 + 最终清理（幂等）。
        // 预握手（能力位声明）：1.21.1+ 在配置阶段主动上报（login/认证后的原生 C2S 载体）；
        // 1.20.1 login query 应答由 common mixin（MixinClientPacketListener）处理，loader 无需注册。
#if MC_VER >= MC_1_21_1
        ClientConfigurationConnectionEvents.START.register((handler, client) ->
                ClientConfigurationNetworking.send(
                        io.github.limuqy.mc.hassium.network.PreHandshakePayload.create()));
#endif
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientLifecycleHelper.cleanupOnDisconnect();
            // 延后到下一 tick：等 Minecraft.disconnect / clearLevel 拆除完成；与 Mixin TAIL 幂等
            client.execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal);
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();
        // 直连拓扑：HASSIUM 业务 S2C 通道全部经 vanilla CustomPayload 直收（服务端
        // ServerPlayNetworking.send 直发）。客户端 receiver：SHADOW_PULL_RESPONSE_S2C
        // （shadowPullV1 FULL 回退）、PLAY_INIT_S2C（Play 期激活）、
        // LIGHT_DELTA_S2C（光照增量）、DICTIONARY_SYNC/INDEX_SYNC/AGGREGATION（聚合链）。
        // 解包 + 业务分发统一在 common PayloadHandlers（P1b 下沉）；本类只保留
        // 传输面（1.20.1 从 buf 取 byte[] + 线程封送；1.21.1+ payload 自带 byte[]）。
        // CHUNK_PAYLOAD_S2C 通道已退役（纯 Compare+Pull）；CHUNK_HASH/SECTION_DELTA 等区块
        // 核心增量通道由 common 客户端摄入管线（ClientChunkPipeline / ClientMetadataHandler）消费。
        LOGGER.info("Hassium: Fabric client registers direct-play S2C receivers (SHADOW_PULL_RESPONSE_S2C / PLAY_INIT_S2C / LIGHT_DELTA_S2C / DICTIONARY_SYNC_S2C / INDEX_SYNC_S2C / AGGREGATION_S2C).");

        // shadowPullV1 FULL 回退：服务端返回的原版 chunk+light 线格式统一交给 ShadowPullClient 注入。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.SHADOW_PULL_RESPONSE_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = PayloadHandlers.readAll(buf);
                    client.execute(() -> PayloadHandlers.handleShadowPullResponse(data));
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.SHADOW_PULL_RESPONSE_S2C_TYPE,
                (payload, context) -> PayloadHandlers.handleShadowPullResponse(payload.data()));
#endif
        // PLAY_INIT_S2C 客户端 receiver：Play 期激活直收（登录协商结果 + SeedGen 种子）→
        // common PlayInitClient.handle（协商位登记 + 影子端种子初始化；管线级压缩已退役）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.PLAY_INIT_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = PayloadHandlers.readAll(buf);
                    client.execute(() -> PayloadHandlers.handlePlayInit(data));
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.PLAY_INIT_S2C_TYPE,
                (payload, context) -> context.client().execute(() ->
                        PayloadHandlers.handlePlayInit(payload.data())));
#endif
        // LIGHT_DELTA_S2C 客户端 receiver：直连拓扑光照增量回传 → 影子端
        // ShadowLightCompute.submitLightDelta（任意线程安全；与 Forge/NeoForge receiver 同消费）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.LIGHT_DELTA_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = PayloadHandlers.readAll(buf);
                    client.execute(() -> PayloadHandlers.handleLightDelta(data));
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.LIGHT_DELTA_S2C_TYPE,
                (payload, context) -> PayloadHandlers.handleLightDelta(payload.data()));
#endif

        // DICTIONARY_SYNC_S2C 客户端 receiver：聚合字典直收（聚合包解码前置条件）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.DICTIONARY_SYNC_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = PayloadHandlers.readAll(buf);
                    client.execute(() -> PayloadHandlers.handleDictionarySync(data));
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.DICTIONARY_SYNC_S2C_TYPE,
                (payload, context) -> context.client().execute(() ->
                        PayloadHandlers.handleDictionarySync(payload.data())));
#endif
        // INDEX_SYNC_S2C 客户端 receiver：包索引登记 → 聚合激活 ACK（aggregation_ready）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.INDEX_SYNC_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = PayloadHandlers.readAll(buf);
                    client.execute(() -> ClientActivation.handleIndexSync(data));
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.INDEX_SYNC_S2C_TYPE,
                (payload, context) -> context.client().execute(() ->
                        ClientActivation.handleIndexSync(payload.data())));
#endif
        // AGGREGATION_S2C 客户端 receiver：聚合帧拆包分发（原版子包重建 + 自定义 payload 回灌）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.AGGREGATION_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = PayloadHandlers.readAll(buf);
                    var connection = handler.getConnection();
                    AggregationDecodeQueue.enqueue(connection, data);
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.AGGREGATION_S2C_TYPE,
                (payload, context) -> AggregationDecodeQueue.enqueueClient(payload.data()));
#endif

        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
}
