package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneHandshakeAdvertisement;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
#if MC_VER >= MC_1_21_1
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
#endif
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.UUID;
import io.netty.channel.Channel;

/**
 * Fabric 平台网络管理器实现。
 * <p>
 * 版本整段切分（见 docs/version-segments.md）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_21_1}：Identifier + FriendlyByteBuf 收发</li>
 *   <li>{@code MC_VER >= MC_1_21_1}：CustomPacketPayload + StreamCodec（{@link FabricPayloadRegistry}）</li>
 * </ul>
 * 禁止在每个 send/receive 再引入碎片分界；common 侧聚合能力由 {@link io.github.limuqy.mc.hassium.compat.NetworkCapability} 门控。
 */
public class FabricNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");

    // review-fix: T10-M2：共享调度器，防每次握手新建单线程调度执行器泄漏线程；JVM 关闭钩子回收
    private static final java.util.concurrent.ScheduledExecutorService PENDING_TIMEOUT_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Hassium-PendingTimeout");
                t.setDaemon(true);
                return t;
            });

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(PENDING_TIMEOUT_SCHEDULER::shutdownNow, "Hassium-PendingTimeoutShutdown"));
    }

    // 缓存服务器实例
    private static volatile net.minecraft.server.MinecraftServer cachedServer;

    /**
     * 设置服务器实例（在服务器启动时调用）
     */
    public static void setServerInstance(net.minecraft.server.MinecraftServer server) {
        cachedServer = server;
    }

    /**
     * 通过反射获取 Connection 的 channel 字段（委托 common 类型匹配实现，SRG/intermediary 安全）
     */
    private static io.netty.channel.Channel getConnectionChannel(Connection connection) {
        return ZstdPipelineSwitcher.getConnectionChannel(connection);
    }

    /**
     * 通过反射获取 ServerPlayer 的 Connection
     */
    private static Connection getPlayerConnection(ServerPlayer player) {
        return io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
    }

    // 资源位置定义
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
HANDSHAKE_C2S = ResourceLocationCompat.create(Constants.MOD_ID, "handshake_c2s");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
PRE_HANDSHAKE_C2S = ResourceLocationCompat.create(Constants.MOD_ID, "prehandshake_c2s");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
HANDSHAKE_S2C = ResourceLocationCompat.create(Constants.MOD_ID, "handshake_s2c");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
COMPRESSION_READY_C2S = ResourceLocationCompat.create(Constants.MOD_ID, "compression_ready_c2s");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
AGGREGATION_S2C = ResourceLocationCompat.create(Constants.MOD_ID, "aggregation");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
DICTIONARY_SYNC_S2C = ResourceLocationCompat.vanilla(HassiumChannels.DICTIONARY_SYNC);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHUNK_DATA_REQUEST_C2S = ResourceLocationCompat.vanilla(HassiumChannels.CHUNK_DATA_REQUEST_C2S);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHUNK_HASH_S2C = ResourceLocationCompat.vanilla(HassiumChannels.CHUNK_HASH_S2C);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
SEED_REF_S2C = ResourceLocationCompat.vanilla(HassiumChannels.SEED_REF_S2C);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
SECTION_HASH_REQUEST_C2S = ResourceLocationCompat.vanilla(HassiumChannels.SECTION_HASH_REQUEST_C2S);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
SECTION_DELTA_S2C = ResourceLocationCompat.vanilla(HassiumChannels.SECTION_DELTA_S2C);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
BLOCK_ENTITY_REQUEST_C2S = ResourceLocationCompat.vanilla(HassiumChannels.BLOCK_ENTITY_REQUEST_C2S);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CLIENT_BLOOM_SYNC_C2S = ResourceLocationCompat.vanilla(HassiumChannels.CLIENT_BLOOM_SYNC_C2S);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
BLOCK_ENTITY_DATA_S2C = ResourceLocationCompat.vanilla(HassiumChannels.BLOCK_ENTITY_DATA_S2C);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHUNK_PAYLOAD_S2C = ResourceLocationCompat.create(Constants.MOD_ID, "chunk_payload_s2c");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
INDEX_SYNC_S2C = ResourceLocationCompat.create(Constants.MOD_ID, "index_sync_s2c");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
LIGHT_DELTA_S2C = ResourceLocationCompat.vanilla(HassiumChannels.LIGHT_DELTA_S2C);

    @Override
    public void registerChannels() {
        // review-fix T11-19：gateway_info 无条件注册（先于 master.enabled 守卫），镜像 NeoForge
        // registerPayloads 语义：ServerGatewayInfoSender.canSend（dedicated + master.enabled）与
        // 本守卫判定可能脱钩，未注册 S2C payload 在 1.20.5+ 类型化通道会被客户端丢弃。
        // MC_VER < MC_1_21_1 无类型化 payload registry，vanilla 直发无需注册，本就无此问题。
#if MC_VER >= MC_1_21_1
        FabricPayloadRegistry.registerGatewayInfo();
#endif
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: master.enabled=false, skipping Fabric channel registration (gateway_info already registered)");
            return;
        }
        LOGGER.debug("Hassium: Registering Fabric network channels");
#if MC_VER >= MC_1_21_1
        FabricPayloadRegistry.registerAll();
#endif
        registerServerChannels();

        // 设置聚合包发送器
        HassiumAggregationManager.setSender((connection, buf) -> {
            if (connection.getPacketListener() instanceof net.minecraft.server.network.ServerGamePacketListenerImpl handler) {
                ServerPlayer player = handler.getPlayer();
#if MC_VER < MC_1_21_1
                ServerPlayNetworking.send(player, AGGREGATION_S2C, buf);
#else
                ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.AGGREGATION_S2C_TYPE, buf));
#endif
            } else {
                LOGGER.error("Cannot send aggregation packet: connection has no player");
                buf.release();
            }
        });

        // 设置字典推送回调
        DictionaryManager.setPushCallback((dictionary) -> {
            try {
                net.minecraft.server.MinecraftServer server = cachedServer;
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        sendDictionarySyncPacket(player, dictionary);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to push dictionary to clients", e);
            }
        });
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_21_1
            ClientPlayNetworking.send(CHUNK_DATA_REQUEST_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_DATA_REQUEST_C2S_TYPE, buf));
#endif
            LOGGER.debug("Hassium: Sent chunk data request");
        } else {
            // 连接不存在，释放缓冲区
            buf.release();
        }
    }

    @Override
    public void sendClientBloomSync(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_21_1
            ClientPlayNetworking.send(CLIENT_BLOOM_SYNC_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CLIENT_BLOOM_SYNC_C2S_TYPE, buf));
#endif
            LOGGER.debug("Hassium: Sent client bloom sync");
        } else {
            // 连接不存在，释放缓冲区
            buf.release();
        }
    }


    // review-fix: T11-14 sendCompressedPayload 退役（common 接口 default no-op，无调用方）

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, CHUNK_HASH_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_HASH_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, SEED_REF_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SEED_REF_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_21_1
            ClientPlayNetworking.send(SECTION_HASH_REQUEST_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SECTION_HASH_REQUEST_C2S_TYPE, buf));
#endif
        } else {
            buf.release();
        }
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // 路由器在数据面未启用、未绑定或无可用会话时返回 false；保留 Primary 路径。
        // 非破坏抽取保持 reader index，以便回退时 Primary 仍能读到完整 payload。
        int len = buf.readableBytes();
        byte[] payload = new byte[len];
        if (len > 0) {
            buf.getBytes(buf.readerIndex(), payload);
        }
        if (io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer.tryRouteBulk(
                player.getUUID(),
                io.github.limuqy.mc.hassium.network.dataplane.DataPlaneFrame.TYPE_BULK_SECTION_DELTA,
                payload)) {
            buf.release();
            return; // 已走 UDP 数据面
        }
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, SECTION_DELTA_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SECTION_DELTA_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_21_1
            ClientPlayNetworking.send(BLOCK_ENTITY_REQUEST_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_REQUEST_C2S_TYPE, buf));
#endif
        } else {
            buf.release();
        }
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, BLOCK_ENTITY_DATA_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_DATA_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // 三端一致收口（2026-08-23 裁决）：vanilla 通道 LightDelta 三端客户端均不消费
        // （Fabric 客户端自 T12 起不注册 HASSIUM 业务 receiver，见 HassiumClientMod），
        // 唯一消费在网关帧链路；本实现仅消费 buf 所有权，不再发 payload。
        buf.release();
    }

    /**
     * 发送已编码的压缩区块负载到指定玩家（payload 由调用方 encode 一次；review-fix: T11-19，
     * 镜像 NeoForge 同名方法，避免内部二次 encode() 的重复分配+拷贝）
     */
    public static void sendCompressedChunk(ServerPlayer player, byte[] data) {
        try {
            DebugLogger.info(LogType.COMPRESSION,
                    "[SEND_CHUNK] Sending compressed chunk to player {} (compressedSize={})",
                    player.getName().getString(), data.length);

            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(data.length);
            buf.writeBytes(data);

            DebugLogger.debug(LogType.NETWORK, "[SEND_CHUNK] Encoded chunk data ({} bytes), sending via network", data.length);
#if MC_VER < MC_1_21_1
            // review-fix: T10-2: send 抛异常时 Fabric 不会释放 buf → 失败路径手动 release 后重抛（成功路径由 Fabric 负责释放）
            try {
                ServerPlayNetworking.send(player, CHUNK_PAYLOAD_S2C, buf);
            } catch (Exception e) {
                buf.release();
                throw e;
            }
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_PAYLOAD_S2C_TYPE, buf));
#endif
        } catch (Exception e) {
            LOGGER.error("[SEND_CHUNK] Failed to send compressed chunk to player {}", player.getName().getString(), e);
        }
    }

    /**
     * 发送字典同步包到指定玩家
     */
    private void sendDictionarySyncPacket(ServerPlayer player) {
        try {
            byte[] aggregationDict = DictionaryManager.getAggregationDict();

            DictionarySyncPayload payload = new DictionarySyncPayload(aggregationDict, false);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            payload.encode(buf);
#if MC_VER < MC_1_21_1
            ServerPlayNetworking.send(player, DICTIONARY_SYNC_S2C, buf);
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.DICTIONARY_SYNC_S2C_TYPE, buf));
#endif
            DebugLogger.debug(LogType.NETWORK, "Hassium: Sent aggregation dictionary sync to player {} ({} bytes)",
                    player.getName().getString(),
                    aggregationDict != null ? aggregationDict.length : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send dictionary sync packet", e);
        }
    }

    /**
     * 发送指定字典到玩家
     */
    private void sendDictionarySyncPacket(ServerPlayer player, byte[] dictionary) {
        try {
            DictionarySyncPayload payload = new DictionarySyncPayload(dictionary, false);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            payload.encode(buf);
#if MC_VER < MC_1_21_1
            ServerPlayNetworking.send(player, DICTIONARY_SYNC_S2C, buf);
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.DICTIONARY_SYNC_S2C_TYPE, buf));
#endif
            DebugLogger.debug(LogType.NETWORK, "Hassium: Pushed new aggregation dictionary to player {} ({} bytes)",
                    player.getName().getString(), dictionary != null ? dictionary.length : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to push dictionary to player {}", player.getName().getString(), e);
        }
    }

    /**
     * 发送索引同步包到指定玩家
     */
    private void sendIndexSyncPacket(ServerPlayer player) {
        try {
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            indexSyncManager.initializeServerIndex();

            IndexSyncPacket syncPacket = indexSyncManager.createSyncPacket();
            byte[] data = syncPacket.encode();

            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
#if MC_VER < MC_1_21_1
            ServerPlayNetworking.send(player, INDEX_SYNC_S2C, buf);
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.INDEX_SYNC_S2C_TYPE, buf));
#endif
            DebugLogger.debug(LogType.NETWORK, "Hassium: Sent index sync packet to player {} ({} packet types)",
                    player.getName().getString(), indexSyncManager.getServerIndexManager().size());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send index sync packet", e);
        }
    }

    /**
     * T7 续流验票（B 侧）：解码票据 → 共享密钥验签 + epoch 递增防重放（ResumeTicketValidator）。
     * 通过 → 标记续流就绪并返回 true；失败/未请求 → false（回退完整握手）。
     */
    private static boolean verifyResumeRequest(ServerPlayer player, HandshakeStateTail.C2S stateTail) {
        if (stateTail == null || !stateTail.resumeRequested()) {
            return false;
        }
        ResumeTicketValidator.Verification resume =
                ResumeTicketValidator.verifyRequest(player.getUUID(), stateTail.resumeTicket());
        if (resume.accepted()) {
            ServerChunkPushManager.getInstance().markPlayerResumeActive(player.getUUID(), resume.epoch());
            LOGGER.info("Hassium: [RESUME] {} ticket verified (epoch={}) — 续流就绪，跳过 login/维度初始化",
                    player.getName().getString(), resume.epoch());
            return true;
        }
        LOGGER.warn("Hassium: [RESUME] {} ticket REJECTED (签名无效/epoch 重放) — 回退完整握手",
                player.getName().getString());
        return false;
    }

    /**
     * 完成服务端握手：发 HandshakeResponse，暂停出站压缩，等客户端 ready ACK 后再切 ZSTD。
     */    private void completeServerHandshake(
            net.minecraft.server.MinecraftServer server,
            ServerPlayer player,
            boolean accepted,
            boolean useGlobalCompression,
            boolean useCompactHeader,
            boolean clientUdpDataplaneSupported,
            boolean clientControlFailoverSupported,
            boolean resumeAccepted) {
        if (useGlobalCompression) {
            DictionaryManager.init();
            IndexSyncManager.getInstance().initializeServerIndex();
            Connection connection = getPlayerConnection(player);
            Channel channel = connection != null ? getConnectionChannel(connection) : null;
            if (channel == null) {
                LOGGER.warn("Hassium: No channel for {}, cannot prepare ZSTD pipeline", player.getName().getString());
            } else {
                // 先暂停出站压缩，再发 HandshakeResponse
                ZstdPipelineSwitcher.pauseOutboundCompression(channel);
            }
        }
        FriendlyByteBuf response = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        response.writeVarInt(Constants.CURRENT_PROTOCOL_VERSION);
        response.writeBoolean(accepted);
        response.writeBoolean(useGlobalCompression);
        response.writeBoolean(useCompactHeader);
        // 追加端点尾部。control 恢复不依赖 UDP bind，因此 control-only 服务器同样创建 epoch 并下发候选。
        if (accepted) {
            try {
                Connection master = getPlayerConnection(player);
                if (master == null) {
                    throw new IllegalStateException("missing player connection");
                }
                long epoch = DataPlaneUdpServer.beginControlConnection(player.getUUID(),
                        () -> master.disconnect(net.minecraft.network.chat.Component.empty()));
                boolean udpBound = DataPlaneUdpServer.isBound();
                UdpDataPlaneHandshakeTail.S2CTail s2cTail = DataPlaneHandshakeAdvertisement.create(
                        DataPlaneUdpServer.advertisedControlEndpoints(),
                        DataPlaneUdpServer.boundEndpoints(),
                        // D-M1: per-player per-epoch bind token（epoch 变更即轮换；旧 token 直接失效）
                        udpBound ? DataPlaneUdpServer.getBindToken(player.getUUID(), epoch) : null,
                        epoch,
                        clientUdpDataplaneSupported && udpBound,
                        clientControlFailoverSupported);
                UdpDataPlaneHandshakeTail.writeS2C(response, s2cTail);
                // SeedGen 尾部（append-only；旧客户端忽略尾字节）
                ServerLevel seedLevel = server.overworld();
                SeedGenTail.writeS2C(response, seedLevel,
                        HassiumConfigService.getInstance().isSeedGenEnabled());
                // T7 续流就绪标记（append-only；旧客户端忽略尾字节；未请求续流时为 false）
                HandshakeStateTail.writeS2C(response, new HandshakeStateTail.S2C(resumeAccepted));
            } catch (Exception ex) {
                LOGGER.warn("Hassium: Failed to append dataplane tail to handshake response for {}",
                        player.getName().getString(), ex);
            }
        }
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, HANDSHAKE_S2C, response);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.HANDSHAKE_S2C_TYPE, response));
#endif
        LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);

        // globalCompression=false 时不会走 CompressionReady→enableAggregation 路径，
        // 必须在此补发视距内 chunkHash，否则握手前 trackChunk 放行的原版包永不进入缓存主链路，
        // 客户端 stats 带宽/区块缓存长期为 0。
        if (accepted && !useGlobalCompression) {
            ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
        }
    }

    /**
     * 客户端 ZSTD ready ACK 后：服务端切管线并发送 Dict/Index。
     */
    private void installServerZstdAfterClientReady(ServerPlayer player, Connection connection, Channel channel) {
        int level = HassiumConfigService.getInstance().getGlobalCompressionLevel();
        int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
        ZstdPipelineSwitcher.switchToZstdWhenReady(channel, threshold, level, () -> {
            ZstdNegotiationTracker.markNegotiated(channel);
            var server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
            Runnable after = () -> enableAggregationAfterZstdSwitch(player, connection);
            if (server != null) {
                server.execute(after);
            } else {
                after.run();
            }
            LOGGER.info("Hassium: Server ZSTD pipeline installed for {}", player.getName().getString());
        });
    }

    /**
     * ZSTD 管线已切换后：发送 Dict/Index，并将连接标为 PENDING。
     */
    private void enableAggregationAfterZstdSwitch(ServerPlayer player, Connection connection) {
        sendDictionarySyncPacket(player);

        IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
        indexSyncManager.initializeServerIndex();
        sendIndexSyncPacket(player);

        if (connection == null) {
            return;
        }
        HassiumConnectionRegistry.markPending(connection);
        ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
        HassiumAggregationManager.init();
        DebugLogger.debug(LogType.NETWORK,
                "Hassium: Marked connection as PENDING for player {}", player.getName().getString());

        String playerName = player.getName().getString();
        PENDING_TIMEOUT_SCHEDULER.schedule(() -> {
            if (HassiumConnectionRegistry.tryDemoteFromPending(connection)) {
                HassiumAggregationManager.discardConnection(connection);
                LOGGER.warn("Hassium: Ack timeout for {}, disabling aggregation", playerName);
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 1.20.1 login 阶段取 GameProfile（预握手标记需要玩家 UUID，但此时尚无 ServerPlayer）。
     * <p>
     * 为什么必须反射：
     * <ul>
     *   <li>1.20.1 的 {@code ServerLoginPacketListenerImpl} 把 profile 存在 package-private
     *       字段 {@code gameProfile}，无任何 public 访问器（{@code getGameProfile()} 是
     *       1.20.2 重构才加的）——fabric 0.92.9 的 login API 回调也只给 loginListener，不暴露 profile。</li>
     *   <li>客户端自报 UUID 不可信（在线服必须用服务端认证后的 profile），不能走握手 payload 上报。</li>
     * </ul>
     * 为什么安全：
     * <ul>
     *   <li>按<b>类型</b>匹配（{@link ReflectionCompat#findFieldByType}）而非字段名——
     *       SRG/intermediary 生产运行时字段名变化免疫（同 {@link PlayerCompat#getConnection} 先例）。</li>
     *   <li>该类的 GameProfile 类型字段唯一，无歧义。</li>
     *   <li>QUERY_START 在 login 完成前触发（fabric 会等待 query 回复）。此时：
     *       在线服——认证线程已完成（state 已到 READY_TO_ACCEPT），profile 带认证 UUID；
     *       离线服——handleHello 只设了 {@code new GameProfile(null, name)}，UUID 派生
     *       （{@code createFakeProfile}）要等 {@code handleAcceptedLogin} 才执行（fabric
     *       queryTick 恰好注入在它 HEAD 之前）——这里按原版同款逻辑提前派生
     *       OfflinePlayer UUID，与 placeNewPlayer 后的 ServerPlayer UUID 一致。</li>
     * </ul>
     */
    private static UUID resolveLoginPlayerId(net.minecraft.server.network.ServerLoginPacketListenerImpl loginListener) {
        try {
            com.mojang.authlib.GameProfile profile = (com.mojang.authlib.GameProfile)
                    io.github.limuqy.mc.hassium.compat.ReflectionCompat.getFieldByType(
                            loginListener, com.mojang.authlib.GameProfile.class, true);
            if (profile == null) {
                return null;
            }
            UUID id = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileId(profile);
            if (id != null) {
                return id;
            }
            // 离线模式：profile 不完整（id=null），派生 OfflinePlayer UUID（原版 createFakeProfile 同款）
            String name = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileName(profile);
            if (name != null) {
                return net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name);
            }
        } catch (Exception e) {
            LOGGER.warn("Hassium: Failed to resolve login player profile (pre-handshake)", e);
        }
        return null;
    }

    /**
     * 服务端预握手注册：{@code MC_VER < MC_1_21_1}（1.20.1）login query；1.21.1+ 配置阶段接收。
     * <p>
     * 收到后仅 {@code PlayerCompressionTracker.markPreHandshake(UUID)}；
     * {@code ServerPlayer} 创建时（{@code MixinServerPlayer} TAIL）自动提升为
     * 压缩启用，完整协商（ZSTD/聚合/数据面/位置）仍在 Play 阶段握手。
     */
    private void registerPreHandshakeServer() {
#if MC_VER < MC_1_21_1
        // 1.20.1：login query 阶段发 query，收到回复后按 UUID 标记预握手。
        // vanilla / 无 mod 客户端对未知 channel 回空（understood=false），不标记。
        ServerLoginConnectionEvents.QUERY_START.register((loginListener, server, sender, synchronizer) -> {
            try {
                // 必须用 sender.createPacket() 让 fabric 的 QueryIdFactory 分配递增 queryId：
                // fabric 内部 early_registration 的 id 从 0 起，硬编码 0 会覆盖其登记，
                // 服务端收到回复时 channels.remove(0) 取不到 → "no query has been associated" 断连。
                // Unpooled 堆缓冲无引用计数；协议层编码时拷贝，无泄漏
                FriendlyByteBuf queryBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                sender.sendPacket(sender.createPacket(PRE_HANDSHAKE_C2S, queryBuf));
            } catch (Exception e) {
                LOGGER.warn("Hassium: Failed to send login pre-handshake query", e);
            }
        });
        ServerLoginNetworking.registerGlobalReceiver(PRE_HANDSHAKE_C2S,
                (server, loginListener, understood, buf, synchronizer, sender) -> {
                    if (!understood) {
                        return; // vanilla / 无 mod 客户端：空回复
                    }
                    // 1.20.1 无 getGameProfile() 访问器，按类型反射取（见 resolveLoginPlayerId javadoc）
                    UUID playerId = resolveLoginPlayerId(loginListener);
                    PreHandshakeProtocol.handlePreHandshake(playerId, buf);
                });
#else
        // 1.21.1+：配置阶段 payload 接收。
        // payload type 注册统一在 FabricPayloadRegistry.registerAll（registerChannels 时调用，幂等）
        ServerConfigurationNetworking.registerGlobalReceiver(PreHandshakePayload.TYPE,
                (payload, context) -> {
                    UUID playerId = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileId(context.networkHandler().getOwner());
                    PreHandshakeProtocol.handlePreHandshake(playerId, payload);
                });
#endif
    }

    /**
     * 注册服务端网络通道
     */
    private void registerServerChannels() {
        // ===== 预握手（login/配置阶段）：提前标记 Hassium 客户端 =====
        registerPreHandshakeServer();
        // 注册握手请求
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(HANDSHAKE_C2S, (server, player, handler, buf, sender) -> {
            int protocolVersion = buf.readVarInt();
            String modVersion = buf.readUtf();
            int algoCount = buf.readVarInt();
            String[] algorithms = new String[algoCount];
            for (int i = 0; i < algoCount; i++) {
                algorithms[i] = buf.readUtf();
            }
            boolean clientCache = buf.readBoolean();
            boolean chunkRevision = buf.readBoolean();
            boolean scheme127 = buf.readBoolean();
            boolean globalPacketCompression = buf.readBoolean();
            boolean compactHeader = buf.readBoolean();
            UdpDataPlaneHandshakeTail.C2STail dataplaneCapabilities = UdpDataPlaneHandshakeTail.readC2S(buf);

            // 握手尾部：客户端上报位置（新版客户端；旧客户端无此字段）
            double reportedX = 0.0;
            double reportedZ = 0.0;
            if (buf.isReadable()) {
                try {
                    reportedX = buf.readDouble();
                    reportedZ = buf.readDouble();
                } catch (Exception ignored) {
                }
            }
            // SeedGen 能力（append-only；旧客户端无此字段）
            boolean seedGenSupported = false;
            if (buf.isReadable()) {
                try {
                    seedGenSupported = buf.readBoolean();
                } catch (Exception ignored) {
                }
            }
            // 光照计算能力（append-only；旧客户端无此字段 → false = 不剥光）
            boolean lightComputeSupported = false;
            if (buf.isReadable()) {
                try {
                    lightComputeSupported = buf.readBoolean();
                } catch (Exception ignored) {
                }
            }
            ServerChunkPushManager.getInstance().setPlayerSeedGenSupported(player.getUUID(), seedGenSupported);
            ServerChunkPushManager.getInstance().setPlayerLightComputeSupported(player.getUUID(), lightComputeSupported);
            // T7 状态尾部（append-only；旧客户端无此字段 → null）
            HandshakeStateTail.C2S stateTail = HandshakeStateTail.readC2S(buf);

            DebugLogger.debug(LogType.NETWORK,
                    "[HANDSHAKE] Details from {}: protocol={}, modVersion={}, algorithms={}, clientCache={}, globalCompression={}, compactHeader={}",
                    player.getName().getString(), protocolVersion, modVersion, String.join(", ", algorithms),
                    clientCache, globalPacketCompression, compactHeader);

            // 启用该玩家的压缩
            PlayerCompressionTracker.enableCompression(player);

            // 检查是否支持全局压缩
            boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
            boolean useGlobalCompression = serverSupportsGlobalCompression && globalPacketCompression;

            // 检查是否支持紧凑包头
            boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
            boolean useCompactHeader = serverSupportsCompactHeader && compactHeader;

            boolean accepted = true;
            final double finalReportedX = reportedX;
            final double finalReportedZ = reportedZ;
            final HandshakeStateTail.C2S finalStateTail = stateTail;

            // 发送握手响应
            // 时序（对齐原版 SetCompression）：
            // 1) HandshakeResponse 先经 Zlib 出站
            // 2) 再在 EventLoop 上切换 ZSTD（排在已排队的 encode 之后）
            // 3) 随后再发 Dict/Index（走 ZSTD）；客户端在收到 HandshakeResponse 后切换
            server.execute(() -> {
                // T7 续流验票（验签 + epoch 防重放）→ 续流就绪 → 复用现有推送链
                boolean resumeAccepted = verifyResumeRequest(player, finalStateTail);
                PlayerStateReport reportedState = finalStateTail != null && finalStateTail.state().present()
                        ? finalStateTail.state()
                        : PlayerStateReport.fromXZ(finalReportedX, finalReportedZ);
                ServerChunkPushManager.getInstance().setInitialPlayerPosition(player, reportedState);
                completeServerHandshake(server, player, accepted, useGlobalCompression, useCompactHeader,
                        dataplaneCapabilities.udpDataplaneSupported(), dataplaneCapabilities.controlFailoverSupported(),
                        resumeAccepted);
            });
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.HANDSHAKE_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                int protocolVersion = buf.readVarInt();
                String modVersion = buf.readUtf();
                int algoCount = buf.readVarInt();
                String[] algorithms = new String[algoCount];
                for (int i = 0; i < algoCount; i++) {
                    algorithms[i] = buf.readUtf();
                }
                boolean clientCache = buf.readBoolean();
                boolean chunkRevision = buf.readBoolean();
                boolean scheme127 = buf.readBoolean();
                boolean globalPacketCompression = buf.readBoolean();
                boolean compactHeader = buf.readBoolean();

                ServerPlayer player = context.player();
                UdpDataPlaneHandshakeTail.C2STail dataplaneCapabilities = UdpDataPlaneHandshakeTail.readC2S(buf);
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);

                // 握手尾部：客户端上报位置（新版客户端；旧客户端无此字段）
                double reportedX = 0.0;
                double reportedZ = 0.0;
                if (buf.isReadable()) {
                    try {
                        reportedX = buf.readDouble();
                        reportedZ = buf.readDouble();
                    } catch (Exception ignored) {
                    }
                }
                // SeedGen 能力（append-only；旧客户端无此字段）
                boolean seedGenSupported = false;
                if (buf.isReadable()) {
                    try {
                        seedGenSupported = buf.readBoolean();
                    } catch (Exception ignored) {
                    }
                }
                ServerChunkPushManager.getInstance().setPlayerSeedGenSupported(player.getUUID(), seedGenSupported);
                // 光照计算能力（append-only；旧客户端无此字段 → false = 不剥光）
                boolean lightComputeSupported = false;
                if (buf.isReadable()) {
                    try {
                        lightComputeSupported = buf.readBoolean();
                    } catch (Exception ignored) {
                    }
                }
                ServerChunkPushManager.getInstance().setPlayerLightComputeSupported(player.getUUID(), lightComputeSupported);
                // T7 状态尾部（append-only；旧客户端无此字段 → null）
                HandshakeStateTail.C2S stateTail = HandshakeStateTail.readC2S(buf);

                DebugLogger.debug(LogType.NETWORK,
                        "[HANDSHAKE] Details from {}: protocol={}, modVersion={}, algorithms={}, clientCache={}, globalCompression={}, compactHeader={}",
                        player.getName().getString(), protocolVersion, modVersion, String.join(", ", algorithms),
                        clientCache, globalPacketCompression, compactHeader);

                // 启用该玩家的压缩
                PlayerCompressionTracker.enableCompression(player);

                // 检查是否支持全局压缩
                boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
                boolean useGlobalCompression = serverSupportsGlobalCompression && globalPacketCompression;

                // 检查是否支持紧凑包头
                boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
                boolean useCompactHeader = serverSupportsCompactHeader && compactHeader;

                boolean accepted = true;
                final double finalReportedX = reportedX;
                final double finalReportedZ = reportedZ;
                final HandshakeStateTail.C2S finalStateTail = stateTail;

                server.execute(() -> {
                    // T7 续流验票（验签 + epoch 防重放）→ 续流就绪 → 复用现有推送链
                    boolean resumeAccepted = verifyResumeRequest(player, finalStateTail);
                    PlayerStateReport reportedState = finalStateTail != null && finalStateTail.state().present()
                            ? finalStateTail.state()
                            : PlayerStateReport.fromXZ(finalReportedX, finalReportedZ);
                    ServerChunkPushManager.getInstance().setInitialPlayerPosition(player, reportedState);
                    completeServerHandshake(server, player, accepted, useGlobalCompression, useCompactHeader,
                            dataplaneCapabilities.udpDataplaneSupported(), dataplaneCapabilities.controlFailoverSupported(),
                            resumeAccepted);
                });
            } catch (Exception e) {
                LOGGER.error("[HANDSHAKE] Failed to handle handshake packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册压缩就绪确认
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(ResourceLocationCompat.vanilla(HassiumChannels.COMPRESSION_READY_C2S), (server, player, handler, buf, sender) -> {
            CompressionReadyPayload payload = CompressionReadyPayload.decode(buf);
            DebugLogger.debug(LogType.NETWORK, "Hassium: Received compression ready from player {}, ready: {}",
                    player.getName().getString(), payload.isReady());

            if (payload.isReady()) {
                handleCompressionReadyServer(player);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.COMPRESSION_READY_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                CompressionReadyPayload readyPayload = CompressionReadyPayload.decode(buf);
                DebugLogger.debug(LogType.NETWORK, "Hassium: Received compression ready from player {}, ready: {}",
                        context.player().getName().getString(), readyPayload.isReady());

                if (readyPayload.isReady()) {
                    handleCompressionReadyServer(context.player());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to handle compression ready packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册区块数据请求（新协议）
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(CHUNK_DATA_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            try {
                DebugLogger.debug(LogType.NETWORK, "[SERVER] Received chunk data request from player {}",
                        player.getName().getString());
                // review-fix: T10-1: 直接 decode 原 buf（Fabric 回调结束后负责释放），避免副本泄漏
                ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                DebugLogger.debug(LogType.NETWORK, "[SERVER] Decoded chunk data request: {} chunks, dimension={}",
                        request.chunks().size(), request.dimension());

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance()
                                .handleClientChunkDataRequest(player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle chunk data request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode chunk data request", e);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.CHUNK_DATA_REQUEST_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ServerPlayer player = context.player();
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
                DebugLogger.debug(LogType.NETWORK, "[SERVER] Received chunk data request from player {}",
                        player.getName().getString());
                ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                DebugLogger.debug(LogType.NETWORK, "[SERVER] Decoded chunk data request: {} chunks, dimension={}",
                        request.chunks().size(), request.dimension());

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance()
                                .handleClientChunkDataRequest(player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle chunk data request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode chunk data request", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册 section 哈希请求（阶段二）
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(SECTION_HASH_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            try {
                // review-fix: T10-1: 直接 decode 原 buf（Fabric 回调结束后负责释放），避免副本泄漏
                SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleSectionHashRequest(
                                player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle section hash request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode section hash request", e);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.SECTION_HASH_REQUEST_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ServerPlayer player = context.player();
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
                SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleSectionHashRequest(
                                player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle section hash request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode section hash request", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册 blockEntity 数据请求
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(BLOCK_ENTITY_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            try {
                // review-fix: T10-1: 直接 decode 原 buf（Fabric 回调结束后负责释放），避免副本泄漏
                BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleBlockEntityRequest(
                                player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle block entity request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode block entity request", e);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.BLOCK_ENTITY_REQUEST_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ServerPlayer player = context.player();
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
                BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleBlockEntityRequest(
                                player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle block entity request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode block entity request", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册客户端缓存 Bloom 位图同步（C2S）
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(CLIENT_BLOOM_SYNC_C2S, (server, player, handler, buf, sender) -> {
            try {
                // review-fix: T10-1: 直接 decode 原 buf（Fabric 回调结束后负责释放），避免副本泄漏
                ClientBloomSyncPacket packet = ClientBloomSyncPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleClientBloomSync(player, packet);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle client bloom sync", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode client bloom sync", e);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.CLIENT_BLOOM_SYNC_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ServerPlayer player = context.player();
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
                ClientBloomSyncPacket packet = ClientBloomSyncPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleClientBloomSync(player, packet);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle client bloom sync", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode client bloom sync", e);
            } finally {
                buf.release();
            }
        });
#endif
    }

    private void handleCompressionReadyServer(ServerPlayer player) {
        Connection connection = getPlayerConnection(player);
        Channel channel = connection != null ? getConnectionChannel(connection) : null;
        if (channel != null && !ZstdPipelineSwitcher.isZstdInstalled(channel)) {
            installServerZstdAfterClientReady(player, connection, channel);
            return;
        }
        if (connection != null) {
            HassiumConnectionRegistry.markEnabled(connection);
            HassiumAggregationManager.flushConnection(connection);
            DebugLogger.debug(LogType.NETWORK,
                    "Hassium: Marked connection as ENABLED for player {}, flushing buffered packets",
                    player.getName().getString());
        }
    }
}
