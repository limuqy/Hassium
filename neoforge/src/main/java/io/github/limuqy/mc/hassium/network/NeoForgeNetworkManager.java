package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import io.github.limuqy.mc.hassium.network.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.network.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.network.handshake.PlayInitClient;
import io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;

/**
 * NeoForge 平台网络管理器实现。
 * <p>
 * NeoForge ≥1.21.1：Payload + StreamCodec（1.20.1 的 SimpleChannel 兼容线已随 NeoForge 1.20.1 支持退役）。
 * common 聚合能力由 {@link io.github.limuqy.mc.hassium.compat.NetworkCapability} 门控。
 */
public class NeoForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeNetwork");
    private static final String PROTOCOL_VERSION = "1";
    private static final ShadowPullHandler SHADOW_PULL_HANDLER =
            new ShadowPullHandler(new ShadowPullRequestLedger());


    // 缓存服务器实例
    private static volatile net.minecraft.server.MinecraftServer cachedServer;

    /**
     * 设置服务器实例
     */
    public static void setServerInstance(net.minecraft.server.MinecraftServer server) {
        cachedServer = server;
    }

    /**
     * 通过反射获取 ServerPlayer 的 Connection
     */
    private static net.minecraft.network.Connection getPlayerConnection(ServerPlayer player) {
        return io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
    }

    // 1.21.1+: 使用 Payload + StreamCodec

    public record ShadowPullRequestPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<ShadowPullRequestPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "shadow_pull_request_c2s"));
        public static final StreamCodec<FriendlyByteBuf, ShadowPullRequestPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, ShadowPullRequestPayload::data,
                        ShadowPullRequestPayload::new);
        @Override public Type<ShadowPullRequestPayload> type() { return TYPE; }
    }

    public record ShadowPullResponsePayload(byte[] data) implements CustomPacketPayload {
        public static final Type<ShadowPullResponsePayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "shadow_pull_response_s2c"));
        public static final StreamCodec<FriendlyByteBuf, ShadowPullResponsePayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, ShadowPullResponsePayload::data,
                        ShadowPullResponsePayload::new);
        @Override public Type<ShadowPullResponsePayload> type() { return TYPE; }
    }

    /**
     * 客户端缓存 Bloom 位图同步 Payload (C2S)
     */

    /**
     * 区块哈希 Payload (S2C)
     */

    /**
     * BlockEntity 请求 Payload (C2S)
     */
    public record BlockEntityRequestPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<BlockEntityRequestPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "block_entity_request_c2s")
        );

        public static final StreamCodec<FriendlyByteBuf, BlockEntityRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, BlockEntityRequestPayload::data,
                BlockEntityRequestPayload::new
        );

        @Override
        public Type<BlockEntityRequestPayload> type() {
            return TYPE;
        }
    }

    /**
     * BlockEntity 数据 Payload (S2C)
     */
    public record BlockEntityDataPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<BlockEntityDataPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "block_entity_data_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, BlockEntityDataPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, BlockEntityDataPayload::data,
                BlockEntityDataPayload::new
        );

        @Override
        public Type<BlockEntityDataPayload> type() {
            return TYPE;
        }
    }

    /**
     * 光照增量通知 Payload (S2C)
     */
    public record LightDeltaPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<LightDeltaPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "light_delta_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, LightDeltaPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, LightDeltaPayload::data,
                LightDeltaPayload::new
        );

        @Override
        public Type<LightDeltaPayload> type() {
            return TYPE;
        }
    }

    public record DictionarySyncNeoPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<DictionarySyncNeoPayload> TYPE = new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.DICTIONARY_SYNC));
        public static final StreamCodec<FriendlyByteBuf, DictionarySyncNeoPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, DictionarySyncNeoPayload::data,
                DictionarySyncNeoPayload::new
        );
        @Override
        public Type<DictionarySyncNeoPayload> type() {
            return TYPE;
        }
    }

    public record IndexSyncNeoPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<IndexSyncNeoPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "index_sync_s2c")
        );
        public static final StreamCodec<FriendlyByteBuf, IndexSyncNeoPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, IndexSyncNeoPayload::data,
                IndexSyncNeoPayload::new
        );
        @Override
        public Type<IndexSyncNeoPayload> type() {
            return TYPE;
        }
    }

    public record CompressionReadyNeoPayload(boolean ready) implements CustomPacketPayload {
        public static final Type<CompressionReadyNeoPayload> TYPE = new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.COMPRESSION_READY_C2S));
        public static final StreamCodec<FriendlyByteBuf, CompressionReadyNeoPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.ready()),
                buf -> new CompressionReadyNeoPayload(buf.readBoolean())
        );
        @Override
        public Type<CompressionReadyNeoPayload> type() {
            return TYPE;
        }
    }

    /**
     * Play 期激活 Payload (S2C)：登录协商结果 + SeedGen 种子
     * （线格式 = common {@link LoginHandshake.PlayInitPayload}）。
     */
    public record PlayInitNeoPayload(int negotiatedCaps, long worldSeed, byte[] stemNbt,
                                     boolean seedGenEnabled) implements CustomPacketPayload {
        public static final Type<PlayInitNeoPayload> TYPE =
                new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.PLAY_INIT_S2C));

        public static final StreamCodec<FriendlyByteBuf, PlayInitNeoPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> new LoginHandshake.PlayInitPayload(
                        p.negotiatedCaps(), p.worldSeed(), p.stemNbt(), p.seedGenEnabled()).encode(buf),
                buf -> {
                    LoginHandshake.PlayInitPayload payload = LoginHandshake.PlayInitPayload.decode(buf);
                    return new PlayInitNeoPayload(payload.negotiatedCaps(), payload.worldSeed(),
                            payload.stemNbt(), payload.seedGenEnabled());
                }
        );

        @Override
        public Type<PlayInitNeoPayload> type() {
            return TYPE;
        }
    }


    // ========== 注册方法 ==========

    @Override
    public void registerChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()
                && !HassiumConfigService.getInstance().isClientCacheEnabled()) {
            LOGGER.warn("Hassium: master.enabled=false and chunk.enabled=false, skipping NeoForge channel registration");
            return;
        }
        LOGGER.debug("Hassium: NeoForge network channels will be registered via event");
    }

    /**
     * 注册所有 Payload (1.21.1+)
     */
    private static void handlePreHandshake(PreHandshakePayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        // 配置阶段无 ServerPlayer：按 listener owner（GameProfile）UUID 标记，
        // ServerPlayer 创建时（MixinServerPlayer TAIL）自动提升为压缩启用。
        // Play 期激活（协商结果 + SeedGen 种子）经 play_init_s2c 下发（ServerHandshakeActivation）。
        UUID playerId = null;
        if (context.listener() instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl configListener) {
            playerId = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileId(configListener.getOwner());
        }
        PreHandshakeProtocol.handlePreHandshake(playerId, payload);
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // play_init S2C 必须无条件注册（先于下方守卫）：登录协商位非零即下发 play_init
        //（即便 net/master 全关，SHADOW_PULL 位恒协商成功）——NeoForge checkPacket 对未注册
        // S2C payload 直接抛异常炸 tick。服务端不发送时注册无副作用。
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(
                PlayInitNeoPayload.TYPE,
                PlayInitNeoPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        PlayInitClient.handle(new LoginHandshake.PlayInitPayload(
                                payload.negotiatedCaps(), payload.worldSeed(),
                                payload.stemNbt(), payload.seedGenEnabled())))
        );

        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()
                && !HassiumConfigService.getInstance().isClientCacheEnabled()) {
            LOGGER.warn("Hassium: network core and chunk cache disabled, skipping remaining NeoForge Payload registration");
            return;
        }
        LOGGER.debug("Hassium: Registering NeoForge Payload handlers");


        // 注册预握手 (C2S, 配置阶段)：提前标记 Hassium 客户端，
        // ServerPlayer 创建时自动提升压缩 → 进服第一圈 sendChunk 全走 Hassium 链
        registrar.configurationToServer(
                PreHandshakePayload.TYPE,
                PreHandshakePayload.STREAM_CODEC,
                (payload, context) -> handlePreHandshake(payload, context)
        );


        registrar.playToServer(ShadowPullRequestPayload.TYPE, ShadowPullRequestPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleShadowPullRequest);
        registrar.playToClient(ShadowPullResponsePayload.TYPE, ShadowPullResponsePayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleShadowPullResponse);


        // 注册 BlockEntity 请求 (C2S)
        registrar.playToServer(
                BlockEntityRequestPayload.TYPE,
                BlockEntityRequestPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleBlockEntityRequest
        );

        registrar.playToServer(
                CompressionReadyNeoPayload.TYPE,
                CompressionReadyNeoPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player && payload.ready()) {
                        // 直连拓扑：转调 common 激活链（服务端 ZSTD 切换 + Dict/Index 同步 + 聚合放行）
                        ServerHandshakeActivation.handleActivationReady(player);
                    }
                })
        );

        // ===== S2C（客户端处理；与服务端发送方向一一对应）=====

        // BlockEntityData S2C
        registrar.playToClient(BlockEntityDataPayload.TYPE, BlockEntityDataPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleBlockEntityDataS2C);

        // LightDelta S2C（直连拓扑：网关帧链路已裁剪，客户端影子端经 vanilla 通道消费）
        registrar.playToClient(LightDeltaPayload.TYPE, LightDeltaPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    try {
                        LightDeltaS2CPacket packet = LightDeltaS2CPacket.decode(
                                new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data())));
                        ShadowLightCompute.submitLightDelta(packet);
                    } catch (Exception e) {
                        LOGGER.error("[CLIENT] Failed to handle light delta", e);
                    }
                });

        // 字典同步 S2C
        registrar.playToClient(DictionarySyncNeoPayload.TYPE, DictionarySyncNeoPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleDictionarySyncS2C);

        // 索引同步 S2C
        registrar.playToClient(IndexSyncNeoPayload.TYPE, IndexSyncNeoPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleIndexSyncS2C);

        LOGGER.info("Hassium: Registered all NeoForge payload handlers");
    }

    private static void handleShadowPullRequest(ShadowPullRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            FriendlyByteBuf buf = null;
            FriendlyByteBuf out = null;
            try {
                if (payload == null || payload.data() == null) {
                    throw new IllegalArgumentException("shadowPullV1 request payload is null");
                }
                buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                ShadowPullRequestC2SPacket request = ShadowPullRequestC2SPacket.decode(buf);
                String dimension = io.github.limuqy.mc.hassium.compat.LevelCompat.getDimensionId(player.level());
                ShadowPullResponseS2CPacket response = SHADOW_PULL_HANDLER.handle(player.getUUID(), request,
                        dimension, request.epoch(), player.chunkPosition().x, player.chunkPosition().z,
                        io.github.limuqy.mc.hassium.compat.PlayerCompat.getViewDistance(player)
                                + io.github.limuqy.mc.hassium.network.ShadowPullRadii.AUTHORITY_MARGIN,
                        true, player.isAlive() && !player.hasDisconnected(),
                        (req, entry) -> ServerChunkPushManager.getInstance().resolveShadowPull(player, req, entry, dimension));
                out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                response.encode(out);
                byte[] data = new byte[out.readableBytes()];
                out.readBytes(data);
                sendServerPayload(player, new ShadowPullResponsePayload(data));
            } catch (Exception e) {
                LOGGER.warn("[SERVER] Failed to handle shadowPullV1 request", e);
            } finally {
                if (buf != null) {
                    buf.release();
                }
                if (out != null) {
                    out.release();
                }
            }
        });
    }

    private static void handleShadowPullResponse(ShadowPullResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FriendlyByteBuf buf = null;
            try {
                if (payload == null || payload.data() == null) {
                    throw new IllegalArgumentException("shadowPullV1 response payload is null");
                }
                buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                ShadowPullClient.handleResponse(ShadowPullResponseS2CPacket.decode(buf));
            } catch (Exception e) {
                LOGGER.warn("[CLIENT] Failed to handle shadowPullV1 response", e);
            } finally {
                if (buf != null) {
                    buf.release();
                }
            }
        });
    }



    private static void handleBlockEntityRequest(BlockEntityRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.player() instanceof ServerPlayer player) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                    BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);
                    ServerChunkPushManager.getInstance().handleBlockEntityRequest(player, request);
                }
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to handle block entity request", e);
            }
        });
    }

    // ===== S2C 客户端处理 =====

    private static void handleBlockEntityDataS2C(BlockEntityDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
                ClientMetadataHandler.handleBlockEntityDataPacket(packet);
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle block entity data", e);
            }
        });
    }

    private static void handleDictionarySyncS2C(DictionarySyncNeoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> handleDictionarySyncClient(payload.data()));
    }

    private static void handleIndexSyncS2C(IndexSyncNeoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> handleIndexSyncClient(payload.data()));
    }

    // ========== 发送方法实现 ==========


    /** NeoForge payload 发送必须经服务端主线程，避免异步推送批次丢失。 */
    private static void sendServerPayload(ServerPlayer player, CustomPacketPayload payload) {
        net.minecraft.server.MinecraftServer server =
                io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
        if (server != null) {
            server.execute(() -> player.connection.send(payload));
        }
    }

    @Override
    public void sendShadowPullResponse(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        sendServerPayload(player, new ShadowPullResponsePayload(data));
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            BlockEntityRequestPayload payload = new BlockEntityRequestPayload(data);
            net.minecraft.client.Minecraft.getInstance().getConnection().send(payload);
            LOGGER.debug("Hassium: Sent block entity request");
        } else {
            buf.release();
        }
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        BlockEntityDataPayload payload = new BlockEntityDataPayload(data);
        sendServerPayload(player, payload);
        LOGGER.debug("Hassium: Sent block entity data packet to {}", player.getName().getString());
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // 直连拓扑：网关帧链路（LIGHT_DELTA 原唯一消费方）已裁剪，改经 vanilla play S2C payload
        // 下发，客户端影子端 ShadowLightCompute 直连消费（任意线程安全）。
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        sendServerPayload(player, new LightDeltaPayload(data));
    }

    /**
     * 发送 Play 期激活包（协商结果 + SeedGen 种子；直连拓扑 S2C payload）。
     */
    public static void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                                    byte[] stemNbt, boolean seedGenEnabled) {
        try {
            sendServerPayload(player, new PlayInitNeoPayload(
                    negotiatedCaps, worldSeed, stemNbt, seedGenEnabled));
            LOGGER.debug("Hassium: Sent play init to {} (caps={})",
                    player.getName().getString(), LoginHandshake.describeCaps(negotiatedCaps));
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send play init packet", e);
        }
    }


    /** 发送聚合字典同步到客户端（SPI：Services.NETWORK_MANAGER.sendDictionarySync 转调）。 */
    public static void sendDictionarySyncPacket(ServerPlayer player) {
        try {
            byte[] aggregationDict = DictionaryManager.getAggregationDict();
            if (aggregationDict == null) {
                aggregationDict = new byte[0];
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            new DictionarySyncPayload(aggregationDict, false).encode(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            player.connection.send(new DictionarySyncNeoPayload(data));
            LOGGER.debug("Hassium: Sent dictionary sync ({} bytes) to {}", aggregationDict.length, player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send dictionary sync packet", e);
        }
    }

    /** 发送包索引同步到客户端（SPI：Services.NETWORK_MANAGER.sendIndexSync 转调）。 */
    public static void sendIndexSyncPacket(ServerPlayer player) {
        try {
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            indexSyncManager.initializeServerIndex();
            IndexSyncPacket syncPacket = indexSyncManager.createSyncPacket();
            byte[] encoded = syncPacket.encode();
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(encoded.length);
            buf.writeBytes(encoded);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            player.connection.send(new IndexSyncNeoPayload(data));
            LOGGER.debug("Hassium: Sent index sync to {}", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send index sync packet", e);
        }
    }

    private static void handleDictionarySyncClient(byte[] data) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
            DictionarySyncPayload payload = DictionarySyncPayload.decode(buf);
            DictionaryManager.setAggregationDict(payload.dictionary());
            LOGGER.debug("Hassium: Received aggregation dictionary ({} bytes)",
                    payload.dictionary() != null ? payload.dictionary().length : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle dictionary sync", e);
        }
    }

    private static void handleIndexSyncClient(byte[] data) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
            int dataLength = buf.readVarInt();
            byte[] packetData = new byte[dataLength];
            buf.readBytes(packetData);
            IndexSyncPacket syncPacket = IndexSyncPacket.decode(packetData);
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            NamespaceIndexManager clientIndexManager = indexSyncManager.handleSyncPacket("client", syncPacket);

            var conn = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (conn != null) {
                Connection connection = conn.getConnection();
                HassiumConnectionRegistry.markEnabled(connection);
                HassiumAggregationManager.init();
                sendCompressionReadyToServer();
            }
            LOGGER.debug("Hassium: Received index sync ({} types), sent compression ready",
                    clientIndexManager.size());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle index sync", e);
        }
    }

    private static void handleAggregationClient(byte[] data) {
        FriendlyByteBuf packetBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
        try {
            var clientConn = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (clientConn == null) {
                LOGGER.error("Received aggregation packet but no client connection");
                return;
            }
            NamespaceIndexManager indexManager = IndexSyncManager.getInstance().getClientIndexManager();
            if (indexManager == null) {
                LOGGER.error("Received aggregation packet but client index manager not initialized");
                return;
            }
            HassiumAggregationPacket.decode(packetBuf, indexManager).handle(clientConn.getConnection());
        } catch (Throwable e) { // review-fix: T13-C1（decode 校验抛 IllegalArgumentException/Error 均须收敛，防 OOM 后链路悬挂）
            LOGGER.error("Failed to handle aggregation packet", e);
        } finally {
            packetBuf.release();
        }
    }

    /**
     * 配置阶段 C2S 能力声明（MixinClientConfigurationPacketListenerImpl 每连接一次性调用）。
     * <p>
     * 配置期 {@code Minecraft.getConnection()} 恒为 null（play listener 未创建），必须用
     * mixin 反射取出的配置监听器 connection 直发 vanilla 自定义包；NeoForge 按当前
     * CONFIGURATION 协议分派 {@code configurationToServer} 注册的 codec（未注册 id 才落
     * DiscardedPayload，原版客户端零干扰）。
     */
    public static void announcePreHandshake(net.minecraft.network.Connection connection) {
        if (connection != null && connection.isConnected()) {
            connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    io.github.limuqy.mc.hassium.network.PreHandshakePayload.create()));
        }
    }
    public static void sendCompressionReadyToServer() {
        try {
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(new CompressionReadyNeoPayload(true));
            }
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send compression ready", e);
        }
    }

}


