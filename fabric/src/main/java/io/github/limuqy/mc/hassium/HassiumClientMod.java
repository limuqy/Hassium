package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.network.handshake.PlayInitClient;
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
        // ServerPlayNetworking.send 直发）。客户端 receiver：CHUNK_PAYLOAD_S2C（全量压缩区块）、
        // SHADOW_PULL_RESPONSE_S2C（shadowPullV1 FULL 回退）、SEED_REF_S2C（pristine 区块引用）、
        // PLAY_INIT_S2C（Play 期激活）、LIGHT_DELTA_S2C（光照增量）、DICTIONARY_SYNC/INDEX_SYNC/
        // AGGREGATION（聚合链）。CHUNK_HASH/SECTION_DELTA 等区块核心增量通道由
        // common 客户端摄入管线（ClientChunkPipeline / ClientMetadataHandler）消费。
        LOGGER.info("Hassium: Fabric client registers direct-play S2C receivers (CHUNK_PAYLOAD_S2C / SHADOW_PULL_RESPONSE_S2C / SEED_REF_S2C / PLAY_INIT_S2C / LIGHT_DELTA_S2C / DICTIONARY_SYNC_S2C / INDEX_SYNC_S2C / AGGREGATION_S2C).");

        // CHUNK_PAYLOAD_S2C 客户端 receiver：全量压缩区块直收。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.CHUNK_PAYLOAD_S2C,
                (client, handler, buf, responseSender) -> {
                    int len = buf.readVarInt();
                    byte[] data = new byte[len];
                    buf.readBytes(data);
                    ClientChunkHandler.handleCompressedChunk(data);
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.CHUNK_PAYLOAD_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf buf = io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    int len = buf.readVarInt();
                    byte[] data = new byte[len];
                    buf.readBytes(data);
                    ClientChunkHandler.handleCompressedChunk(data);
                });
#endif
        // shadowPullV1 FULL 回退：服务端返回的原版 chunk+light 线格式统一交给 ShadowPullClient 注入。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.SHADOW_PULL_RESPONSE_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf response = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            io.github.limuqy.mc.hassium.network.ShadowPullClient.handleResponse(
                                    io.github.limuqy.mc.hassium.network.ShadowPullResponseS2CPacket.decode(response));
                        } finally {
                            response.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.SHADOW_PULL_RESPONSE_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf response =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        io.github.limuqy.mc.hassium.network.ShadowPullClient.handleResponse(
                                io.github.limuqy.mc.hassium.network.ShadowPullResponseS2CPacket.decode(response));
                    } finally {
                        response.release();
                    }
                });
#endif
        // SEED_REF_S2C 客户端 receiver：SeedRef（pristine 区块引用）直收 → 本地生成。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.SEED_REF_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf seedRefBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            io.github.limuqy.mc.hassium.network.ClientMetadataHandler.handleSeedRefPacket(
                                    io.github.limuqy.mc.hassium.network.SeedRefS2CPacket.decode(seedRefBuf));
                        } finally {
                            seedRefBuf.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.SEED_REF_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf seedRefBuf =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        io.github.limuqy.mc.hassium.network.ClientMetadataHandler.handleSeedRefPacket(
                                io.github.limuqy.mc.hassium.network.SeedRefS2CPacket.decode(seedRefBuf));
                    } finally {
                        seedRefBuf.release();
                    }
                });
#endif
        // PLAY_INIT_S2C 客户端 receiver：Play 期激活直收（登录协商结果 + SeedGen 种子）→
        // common PlayInitClient.handle（协商位登记 + 影子端种子初始化；管线级压缩已退役）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.PLAY_INIT_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf playInitBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            PlayInitClient.handle(LoginHandshake.PlayInitPayload.decode(playInitBuf));
                        } finally {
                            playInitBuf.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.PLAY_INIT_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf playInitBuf =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        LoginHandshake.PlayInitPayload playInit =
                                LoginHandshake.PlayInitPayload.decode(playInitBuf);
                        context.client().execute(() -> PlayInitClient.handle(playInit));
                    } finally {
                        playInitBuf.release();
                    }
                });
#endif
        // LIGHT_DELTA_S2C 客户端 receiver：直连拓扑光照增量回传 → 影子端
        // ShadowLightCompute.submitLightDelta（任意线程安全；与 Forge/NeoForge receiver 同消费）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.LIGHT_DELTA_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf lightBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submitLightDelta(
                                    io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket.decode(lightBuf));
                        } finally {
                            lightBuf.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.LIGHT_DELTA_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf lightBuf =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submitLightDelta(
                                io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket.decode(lightBuf));
                    } finally {
                        lightBuf.release();
                    }
                });
#endif

        // DICTIONARY_SYNC_S2C 客户端 receiver：聚合字典直收（聚合包解码前置条件）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.DICTIONARY_SYNC_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf dictBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            io.github.limuqy.mc.hassium.network.DictionarySyncPayload payload =
                                    io.github.limuqy.mc.hassium.network.DictionarySyncPayload.decode(dictBuf);
                            DictionaryManager.setAggregationDict(payload.dictionary());
                        } finally {
                            dictBuf.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.DICTIONARY_SYNC_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf dictBuf =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        io.github.limuqy.mc.hassium.network.DictionarySyncPayload dict =
                                io.github.limuqy.mc.hassium.network.DictionarySyncPayload.decode(dictBuf);
                        context.client().execute(() ->
                                DictionaryManager.setAggregationDict(dict.dictionary()));
                    } finally {
                        dictBuf.release();
                    }
                });
#endif
        // INDEX_SYNC_S2C 客户端 receiver：包索引登记 → 聚合激活 ACK（compression_ready）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.INDEX_SYNC_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf indexBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            int dataLength = indexBuf.readVarInt();
                            byte[] packetData = new byte[dataLength];
                            indexBuf.readBytes(packetData);
                            io.github.limuqy.mc.hassium.network.IndexSyncPacket syncPacket =
                                    io.github.limuqy.mc.hassium.network.IndexSyncPacket.decode(packetData);
                            io.github.limuqy.mc.hassium.network.IndexSyncManager.getInstance()
                                    .handleSyncPacket("client", syncPacket);
                            var conn = client.getConnection();
                            if (conn != null) {
                                io.github.limuqy.mc.hassium.network.HassiumConnectionRegistry.markEnabled(
                                        conn.getConnection());
                                io.github.limuqy.mc.hassium.network.HassiumAggregationManager.init();
                                io.github.limuqy.mc.hassium.network.FabricNetworkManager.sendCompressionReady();
                            }
                        } finally {
                            indexBuf.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.INDEX_SYNC_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf indexBuf =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        int dataLength = indexBuf.readVarInt();
                        byte[] packetData = new byte[dataLength];
                        indexBuf.readBytes(packetData);
                        io.github.limuqy.mc.hassium.network.IndexSyncPacket syncPacket =
                                io.github.limuqy.mc.hassium.network.IndexSyncPacket.decode(packetData);
                        context.client().execute(() -> {
                            io.github.limuqy.mc.hassium.network.IndexSyncManager.getInstance()
                                    .handleSyncPacket("client", syncPacket);
                            var conn = context.client().getConnection();
                            if (conn != null) {
                                io.github.limuqy.mc.hassium.network.HassiumConnectionRegistry.markEnabled(
                                        conn.getConnection());
                                io.github.limuqy.mc.hassium.network.HassiumAggregationManager.init();
                                io.github.limuqy.mc.hassium.network.FabricNetworkManager.sendCompressionReady();
                            }
                        });
                    } finally {
                        indexBuf.release();
                    }
                });
#endif
        // AGGREGATION_S2C 客户端 receiver：聚合帧拆包分发（原版子包重建 + 自定义 payload 回灌）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.AGGREGATION_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf packetBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            var conn = client.getConnection();
                            if (conn == null) {
                                LOGGER.error("Hassium: Received aggregation packet but no client connection");
                                return;
                            }
                            io.github.limuqy.mc.hassium.network.NamespaceIndexManager indexManager =
                                    io.github.limuqy.mc.hassium.network.IndexSyncManager.getInstance()
                                            .getClientIndexManager();
                            if (indexManager == null) {
                                LOGGER.error("Hassium: Received aggregation packet but client index manager not initialized");
                                return;
                            }
                            io.github.limuqy.mc.hassium.network.HassiumAggregationPacket
                                    .decode(packetBuf, indexManager).handle(conn.getConnection());
                        } catch (Throwable e) {
                            LOGGER.error("Hassium: Failed to handle aggregation packet", e);
                        } finally {
                            packetBuf.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.AGGREGATION_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf packetBuf =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        var conn = context.client().getConnection();
                        if (conn == null) {
                            LOGGER.error("Hassium: Received aggregation packet but no client connection");
                            return;
                        }
                        io.github.limuqy.mc.hassium.network.NamespaceIndexManager indexManager =
                                io.github.limuqy.mc.hassium.network.IndexSyncManager.getInstance()
                                        .getClientIndexManager();
                        if (indexManager == null) {
                            LOGGER.error("Hassium: Received aggregation packet but client index manager not initialized");
                            return;
                        }
                        io.github.limuqy.mc.hassium.network.HassiumAggregationPacket
                                .decode(packetBuf, indexManager).handle(conn.getConnection());
                    } catch (Throwable e) {
                        LOGGER.error("Hassium: Failed to handle aggregation packet", e);
                    } finally {
                        packetBuf.release();
                    }
                });
#endif

        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
}
