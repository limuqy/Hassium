package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_21_1
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
#else
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Function;
#endif

/**
 * Forge 平台网络管理器实现（直连拓扑）。
 * <p>
 * 版本整段切分（见 docs/version-segments.md）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_21_1}：旧 SimpleChannel（NetworkRegistry.newSimpleChannel）</li>
 *   <li>{@code MC_VER >= MC_1_21_1}：Forge 50+ ChannelBuilder + play() Payload 风格 SimpleChannel</li>
 * </ul>
 * <p>
 * 登录期能力协商（{@code LoginCaps}）经 vanilla login query（1.20.1，common mixin）/
 * 配置阶段 pre-handshake C2S（1.21.1+，本类 messageBuilder 注册）完成；Play 期
 * {@code play_init_s2c} 激活、{@code compression_ready} ACK、字典/索引同步均由 common
 * 握手链（{@code ServerHandshakeActivation} / {@code PlayInitClient}）经 SPI 走本通道，
 * 不再存在网关帧协议 / UDP 数据面 / 续流票据（2.0.0 直连裁剪）。
 */
public class ForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");
    private static final String PROTOCOL_VERSION = "1";
    private static final int PROTOCOL_VERSION_INT = 1;
    private static final ShadowPullHandler SHADOW_PULL_HANDLER =
            new ShadowPullHandler(new ShadowPullRequestLedger());

#if MC_VER < MC_1_21_1
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocationCompat.create(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    private static int packetId = 0;
#else
    /** Forge 50+：在 {@link #registerChannels()} 中构建并赋值 */
    // review-fix: T10-10: commonSetup 主线程赋值、netty 线程读取 → volatile 保证可见性
    public static volatile SimpleChannel CHANNEL;
#endif

    @Override
    public void registerChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()
                && !HassiumConfigService.getInstance().isClientCacheEnabled()) {
            LOGGER.warn("Hassium: master.enabled=false and chunk.enabled=false, skipping Forge channel registration");
            return;
        }
        LOGGER.debug("Hassium: Registering Forge network channels");
#if MC_VER < MC_1_21_1
        registerLegacyChannels();
#else
        registerModernChannels();
#endif

        HassiumAggregationManager.setSender((connection, buf) -> {
            if (connection.getPacketListener() instanceof net.minecraft.server.network.ServerGamePacketListenerImpl handler) {
                ServerPlayer player = handler.getPlayer();
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                buf.release();
#if MC_VER < MC_1_21_1
                CHANNEL.sendTo(new AggregationWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
                sendToPlayer(player, new AggregationWrapper(data));
#endif
            } else {
                LOGGER.error("Cannot send aggregation packet: connection has no player");
                buf.release();
            }
        });

        // 字典热推回调：服务端字典重建后向全体在线玩家推送 DictionarySync（镜像 NeoForge/NeoForgeNetworkManager）
        DictionaryManager.setPushCallback(dictionary -> {
            try {
                net.minecraft.server.MinecraftServer server =
                        net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        sendDictionarySyncPacket(player);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to push dictionary to clients", e);
            }
        });
    }

#if MC_VER < MC_1_21_1
    private void registerLegacyChannels() {
        // 必须 setPacketHandled(true)（在 enqueueWork 外），否则 Forge 会把包交给原版
        // S2C / C2S 必须带 NetworkDirection，避免方向校验失败

        CHANNEL.<CompressedPayloadWrapper>registerMessage(
                packetId++,
                CompressedPayloadWrapper.class,
                CompressedPayloadWrapper::encode,
                CompressedPayloadWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleCompressedPayload(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.<AggregationWrapper>registerMessage(
                packetId++,
                AggregationWrapper.class,
                AggregationWrapper::encode,
                AggregationWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleAggregationClient(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );




        CHANNEL.<SeedRefWrapper>registerMessage(
                packetId++,
                SeedRefWrapper.class,
                SeedRefWrapper::encode,
                SeedRefWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleSeedRef(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );


        CHANNEL.<BlockEntityRequestWrapper>registerMessage(
                packetId++,
                BlockEntityRequestWrapper.class,
                BlockEntityRequestWrapper::encode,
                BlockEntityRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleBlockEntityRequest(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<BlockEntityDataWrapper>registerMessage(
                packetId++,
                BlockEntityDataWrapper.class,
                BlockEntityDataWrapper::encode,
                BlockEntityDataWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleBlockEntityData(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );


        CHANNEL.<DictionarySyncWrapper>registerMessage(
                packetId++,
                DictionarySyncWrapper.class,
                DictionarySyncWrapper::encode,
                DictionarySyncWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleDictionarySyncClient(msg.data()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.<IndexSyncWrapper>registerMessage(
                packetId++,
                IndexSyncWrapper.class,
                IndexSyncWrapper::encode,
                IndexSyncWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleIndexSyncClient(msg.data()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.<CompressionReadyWrapper>registerMessage(
                packetId++,
                CompressionReadyWrapper.class,
                CompressionReadyWrapper::encode,
                CompressionReadyWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleActivationReadyServer(ctx.get().getSender(), msg.ready()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.<ShadowPullRequestWrapper>registerMessage(
                packetId++, ShadowPullRequestWrapper.class,
                ShadowPullRequestWrapper::encode, ShadowPullRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleShadowPullRequest(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.<ShadowPullResponseWrapper>registerMessage(
                packetId++, ShadowPullResponseWrapper.class,
                ShadowPullResponseWrapper::encode, ShadowPullResponseWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleShadowPullResponse(msg));
                    ctx.get().setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.<PlayInitPayload>registerMessage(
                packetId++, PlayInitPayload.class,
                PlayInitPayload::encode, PlayInitPayload::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> onPlayInitReceived(msg));
                    ctx.get().setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.<LightDeltaWrapper>registerMessage(
                packetId++, LightDeltaWrapper.class,
                LightDeltaWrapper::encode, LightDeltaWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleLightDelta(msg));
                    ctx.get().setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        LOGGER.info("Hassium: Registered {} network packets", packetId);
    }
#else
    private void registerModernChannels() {
        if (CHANNEL != null) {
            LOGGER.debug("Hassium: Forge channel already registered");
            return;
        }

        SimpleChannel channel = ChannelBuilder
                .named(ResourceLocationCompat.create(Constants.MOD_ID, "main"))
                .networkProtocolVersion(PROTOCOL_VERSION_INT)
                .acceptedVersions(Channel.VersionTest.exact(PROTOCOL_VERSION_INT))
                .simpleChannel();

        // 配置阶段预握手（CONFIGURATION_TO_SERVER）：客户端在配置阶段上报声明位
        // （common PreHandshakePayload，3 字段布局 protocolVersion/modVersion/clientCaps），
        // 服务端按位协商并按 owner UUID 登记 → ServerPlayer 创建时（MixinServerPlayer TAIL）
        // 经 ServerHandshakeActivation 消费，Play 期下发 play_init_s2c 激活。
        // 必须在 build() 之前注册：Forge 52.1.15 SimpleChannel.build() 会置 built=true，
        // 之后 messageBuilder 抛 IllegalStateException("SimpleChannel builder is fully built")。
        channel.messageBuilder(PreHandshakePayload.class, NetworkDirection.CONFIGURATION_TO_SERVER)
                .codec(PreHandshakePayload.STREAM_CODEC)
                .consumer(ForgeNetworkManager::onPreHandshake)
                .add();

        CHANNEL = channel
                .play()
                    .serverbound()
                        .addMain(BlockEntityRequestWrapper.class,
                                playCodec(BlockEntityRequestWrapper::encode, BlockEntityRequestWrapper::decode),
                                ForgeNetworkManager::onBlockEntityRequest)
                        .addMain(CompressionReadyWrapper.class,
                                playCodec(CompressionReadyWrapper::encode, CompressionReadyWrapper::decode),
                                ForgeNetworkManager::onCompressionReady)
                        .addMain(ShadowPullRequestWrapper.class,
                                playCodec(ShadowPullRequestWrapper::encode, ShadowPullRequestWrapper::decode),
                                ForgeNetworkManager::onShadowPullRequest)
                    .clientbound()
                        .addMain(CompressedPayloadWrapper.class,
                                playCodec(CompressedPayloadWrapper::encode, CompressedPayloadWrapper::decode),
                                ForgeNetworkManager::onCompressedPayload)
                        .addMain(AggregationWrapper.class,
                                playCodec(AggregationWrapper::encode, AggregationWrapper::decode),
                                ForgeNetworkManager::onAggregationClient)
                        .addMain(BlockEntityDataWrapper.class,
                                playCodec(BlockEntityDataWrapper::encode, BlockEntityDataWrapper::decode),
                                ForgeNetworkManager::onBlockEntityData)
                        .addMain(ShadowPullResponseWrapper.class,
                                playCodec(ShadowPullResponseWrapper::encode, ShadowPullResponseWrapper::decode),
                                ForgeNetworkManager::onShadowPullResponse)
                        .addMain(SeedRefWrapper.class, playCodec(SeedRefWrapper::encode, SeedRefWrapper::decode),
                                ForgeNetworkManager::onSeedRef)
                        .addMain(DictionarySyncWrapper.class,
                                playCodec(DictionarySyncWrapper::encode, DictionarySyncWrapper::decode),
                                ForgeNetworkManager::onDictionarySync)
                        .addMain(IndexSyncWrapper.class, playCodec(IndexSyncWrapper::encode, IndexSyncWrapper::decode),
                                ForgeNetworkManager::onIndexSync)
                        .addMain(PlayInitPayload.class,
                                playCodec(PlayInitPayload::encode, PlayInitPayload::decode),
                                ForgeNetworkManager::onPlayInit)
                        .addMain(LightDeltaWrapper.class,
                                playCodec(LightDeltaWrapper::encode, LightDeltaWrapper::decode),
                                ForgeNetworkManager::onLightDelta)
                .build();
        LOGGER.info("Hassium: Registered Forge 50+ ChannelBuilder play channel (3 C2S + 9 S2C)");
    }

    private static <M> StreamCodec<RegistryFriendlyByteBuf, M> playCodec(
            BiConsumer<M, FriendlyByteBuf> encode,
            Function<FriendlyByteBuf, M> decode
    ) {
        return StreamCodec.of(
                (buf, msg) -> encode.accept(msg, buf),
                buf -> decode.apply(buf)
        );
    }

    private static void onPreHandshake(PreHandshakePayload msg, CustomPayloadEvent.Context ctx) {
        // 配置阶段无 ServerPlayer：按 listener owner（GameProfile）UUID 标记，
        // ServerPlayer 创建时（MixinServerPlayer TAIL）经 ServerHandshakeActivation 消费协商位，
        // Play 期由 drainPending 下发 play_init_s2c 激活。
        java.util.UUID playerId = null;
        if (ctx.getConnection().getPacketListener()
                instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl configListener) {
            playerId = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileId(configListener.getOwner());
        }
        io.github.limuqy.mc.hassium.network.PreHandshakeProtocol.handlePreHandshake(playerId, msg);
    }

    private static void onPlayInit(PlayInitPayload msg, CustomPayloadEvent.Context ctx) {
        // review-fix: T10-M1：consumer 在 netty 线程触发，封送主线程（同 legacy enqueueWork / Fabric server.execute）
        ctx.enqueueWork(() -> onPlayInitReceived(msg));
    }

    private static void onLightDelta(LightDeltaWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleLightDelta(msg));
    }

    private static void onCompressedPayload(CompressedPayloadWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleCompressedPayload(msg));
    }

    private static void onAggregationClient(AggregationWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleAggregationClient(msg));
    }



    private static void onSeedRef(SeedRefWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleSeedRef(msg));
    }

    private static void onShadowPullRequest(ShadowPullRequestWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleShadowPullRequest(msg, ctx.getSender()));
    }

    private static void onShadowPullResponse(ShadowPullResponseWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleShadowPullResponse(msg));
    }


    @Override
    public void sendShadowPullRequest(FriendlyByteBuf buf) {
        if (buf == null) {
            return;
        }
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
#if MC_VER < MC_1_21_1
            if (CHANNEL != null) {
                CHANNEL.sendToServer(new ShadowPullRequestWrapper(data));
            }
#else
            sendToServer(new ShadowPullRequestWrapper(data));
#endif
        } catch (Exception e) {
            LOGGER.warn("Hassium: Failed to send shadowPullV1 request", e);
        } finally {
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
    }



    private static void onBlockEntityRequest(BlockEntityRequestWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleBlockEntityRequest(msg, ctx.getSender()));
    }

    private static void onBlockEntityData(BlockEntityDataWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleBlockEntityData(msg));
    }


    private static void onDictionarySync(DictionarySyncWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleDictionarySyncClient(msg.data()));
    }

    private static void onIndexSync(IndexSyncWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleIndexSyncClient(msg.data()));
    }

    private static void onCompressionReady(CompressionReadyWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleActivationReadyServer(ctx.getSender(), msg.ready()));
    }

    private static void sendToPlayer(ServerPlayer player, Object msg) {
        if (CHANNEL == null) {
            LOGGER.warn("Hassium: CHANNEL not registered, drop packet to {}", player.getName().getString());
            return;
        }
        CHANNEL.send(msg, PacketDistributor.PLAYER.with(player));
    }

    private static void sendToServer(Object msg) {
        if (CHANNEL == null) {
            LOGGER.warn("Hassium: CHANNEL not registered, drop client packet");
            return;
        }
        CHANNEL.send(msg, PacketDistributor.SERVER.noArg());
    }
#endif

    // ========== 辅助方法 ==========


    // ========== 共享处理逻辑 ==========

    /**
     * play_init 客户端 receiver：转调 common {@code PlayInitClient.handle}——
     * 协商位入 ClientLoginNegotiation、globalCompression 协商 → 安装 ZSTD 并回
     * compression_ready、seedGen 协商 → 影子端种子初始化（时序与 1.1.2 期握手一致）。
     */
    private static void onPlayInitReceived(PlayInitPayload msg) {
        io.github.limuqy.mc.hassium.network.handshake.PlayInitClient.handle(
                new io.github.limuqy.mc.hassium.network.handshake.LoginHandshake.PlayInitPayload(
                        msg.negotiatedCaps(), msg.worldSeed(), msg.stemNbt(), msg.seedGenEnabled()));
    }

    /**
     * light_delta 客户端 receiver：直连拓扑光照增量经 vanilla play 通道回传，
     * 消费方为影子端 {@code ShadowLightCompute.submitLightDelta}（任意线程安全）。
     */
    private static void handleLightDelta(LightDeltaWrapper msg) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
        try {
            io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket packet =
                    io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket.decode(buf);
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submitLightDelta(packet);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle light delta packet", e);
        } finally {
            buf.release();
        }
    }

    /**
     * compression_ready 服务端 handler：转调 common {@code ServerHandshakeActivation.handleActivationReady}
     * （首个 ACK → 服务端切 ZSTD 管线 + 发 dictionary_sync/index_sync + registry markPending；
     * 客户端 index_sync 后重发的 ready ACK → registry 提升 ENABLED）。
     */
    private static void handleActivationReadyServer(ServerPlayer player, boolean ready) {
        if (!ready || player == null) {
            return;
        }
        io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation.handleActivationReady(player);
    }

    /**
     * 配置阶段 C2S 能力声明（MixinClientConfigurationPacketListenerImpl 每连接一次性调用）。
     * <p>
     * 配置期 {@code Minecraft.getConnection()} 恒为 null（play listener 未创建），必须用
     * mixin 反射取出的配置监听器 connection 直发 vanilla 自定义包；Forge 按当前
     * CONFIGURATION 协议分派 {@code CONFIGURATION_TO_SERVER} 注册的 codec。
     */
    public static void announcePreHandshake(net.minecraft.network.Connection connection) {
#if MC_VER >= MC_1_21_1
        if (connection != null && connection.isConnected()) {
            connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    io.github.limuqy.mc.hassium.network.PreHandshakePayload.create()));
        }
#endif
    }
    private static void sendCompressionReadyToServer() {
        try {
#if MC_VER < MC_1_21_1
            CHANNEL.sendToServer(new CompressionReadyWrapper(true));
#else
            sendToServer(new CompressionReadyWrapper(true));
#endif
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send compression ready", e);
        }
    }

    private static void handleCompressedPayload(CompressedPayloadWrapper msg) {
        try {
            ClientChunkHandler.handleCompressedChunk(msg.data());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle compressed payload", e);
        }
    }

    private static void handleAggregationClient(AggregationWrapper msg) {

        FriendlyByteBuf packetBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
        try {
            var clientConn = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (clientConn == null) {
                LOGGER.error("Hassium: Received aggregation packet but no client connection");
                return;
            }
            NamespaceIndexManager indexManager = IndexSyncManager.getInstance().getClientIndexManager();
            if (indexManager == null) {
                LOGGER.error("Hassium: Received aggregation packet but client index manager not initialized");
                return;
            }
            HassiumAggregationPacket.decode(packetBuf, indexManager).handle(clientConn.getConnection());
        } catch (Throwable e) { // review-fix: T13-C1（decode 校验抛 IllegalArgumentException/Error 均须收敛，防 OOM 后链路悬挂）
            LOGGER.error("Hassium: Failed to handle aggregation packet", e);
        } finally {
            packetBuf.release();
        }
    }

    private static void handleShadowPullRequest(ShadowPullRequestWrapper msg, ServerPlayer player) {
        if (player == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
        try {
            ShadowPullRequestC2SPacket request = ShadowPullRequestC2SPacket.decode(buf);
            String dimension = io.github.limuqy.mc.hassium.compat.LevelCompat.getDimensionId(player.level());
            ShadowPullResponseS2CPacket response = SHADOW_PULL_HANDLER.handle(player.getUUID(), request,
                    dimension, request.epoch(), player.chunkPosition().x, player.chunkPosition().z,
                    io.github.limuqy.mc.hassium.compat.PlayerCompat.getViewDistance(player)
                            + io.github.limuqy.mc.hassium.network.ShadowPullRadii.AUTHORITY_MARGIN,
                    true, player.isAlive() && !player.hasDisconnected(),
                    (req, entry) -> ServerChunkPushManager.getInstance().resolveShadowPull(player, req, entry, dimension));
            FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                response.encode(out);
                byte[] data = new byte[out.readableBytes()];
                out.readBytes(data);
#if MC_VER < MC_1_21_1
                if (CHANNEL != null) {
                    CHANNEL.sendTo(new ShadowPullResponseWrapper(data),
                            player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                }
#else
                sendToPlayer(player, new ShadowPullResponseWrapper(data));
#endif
            } finally {
                out.release();
            }
        } catch (Exception e) {
            LOGGER.warn("[SERVER] Failed to handle shadowPullV1 request", e);
        } finally {
            buf.release();
        }
    }




    private static void handleShadowPullResponse(ShadowPullResponseWrapper msg) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
        try {
            ShadowPullClient.handleResponse(ShadowPullResponseS2CPacket.decode(buf));
        } catch (Exception e) {
            LOGGER.warn("[CLIENT] Failed to handle shadowPullV1 response", e);
        } finally {
            buf.release();
        }
    }

    private static void handleSeedRef(SeedRefWrapper msg) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            SeedRefS2CPacket packet = SeedRefS2CPacket.decode(buf);
            ClientMetadataHandler.handleSeedRefPacket(packet);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle seed ref packet", e);
        }
    }


    private static void handleBlockEntityRequest(BlockEntityRequestWrapper msg, ServerPlayer player) {
        try {
            if (player == null) {
                return;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);
            ServerChunkPushManager.getInstance().handleBlockEntityRequest(player, request);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle block entity request", e);
        }
    }

    private static void handleBlockEntityData(BlockEntityDataWrapper msg) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
            ClientMetadataHandler.handleBlockEntityDataPacket(packet);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle block entity data packet", e);
        }
    }



    // review-fix: T11-14 sendCompressedPayload 退役（common 接口 default no-op，无调用方）


    @Override
    public void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        CHANNEL.sendTo(new SeedRefWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        sendToPlayer(player, new SeedRefWrapper(data));
#endif
    }
    @Override
    public void sendShadowPullResponse(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        if (CHANNEL != null) {
            CHANNEL.sendTo(new ShadowPullResponseWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
#else
        sendToPlayer(player, new ShadowPullResponseWrapper(data));
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        CHANNEL.sendToServer(new BlockEntityRequestWrapper(data));
#else
        sendToServer(new BlockEntityRequestWrapper(data));
#endif
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        CHANNEL.sendTo(new BlockEntityDataWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        sendToPlayer(player, new BlockEntityDataWrapper(data));
#endif
    }

    @Override
    // 直连拓扑（2026-08-23 裁决修订）：光照增量经 LightDelta play S2C 通道下发，
    // 客户端影子端 ShadowLightCompute 消费。
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        if (CHANNEL != null) {
            CHANNEL.sendTo(new LightDeltaWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
#else
        sendToPlayer(player, new LightDeltaWrapper(data));
#endif
    }

    /**
     * 发送已编码的压缩区块负载到指定玩家（payload 由调用方 encode 一次；review-fix: T11-19）
     */
    public static void sendCompressedChunk(ServerPlayer player, byte[] data) {
        try {
#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new CompressedPayloadWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            sendToPlayer(player, new CompressedPayloadWrapper(data));
#endif
            LOGGER.debug("Hassium: Sent compressed chunk to player {} (size={})",
                    player.getName().getString(), data.length);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send compressed chunk to player {}", player.getName().getString(), e);
        }
    }

    // ========== 数据包记录 ==========

    /**
     * Play 期激活（S2C）：登录协商结果 + SeedGen 种子。
     * wire 布局与 common {@code LoginHandshake.PlayInitPayload} 完全一致
     * （客户端 receiver 转调 {@code PlayInitClient.handle}）。
     */
    public record PlayInitPayload(int negotiatedCaps, long worldSeed, byte[] stemNbt,
                                  boolean seedGenEnabled) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(negotiatedCaps);
            buf.writeLong(worldSeed);
            buf.writeVarInt(stemNbt != null ? stemNbt.length : 0);
            if (stemNbt != null) {
                buf.writeBytes(stemNbt);
            }
            buf.writeBoolean(seedGenEnabled);
        }

        public static PlayInitPayload decode(FriendlyByteBuf buf) {
            int caps = buf.readVarInt();
            long worldSeed = buf.readLong();
            int stemLen = buf.readVarInt();
            byte[] stemNbt = null;
            if (stemLen > 0 && stemLen <= buf.readableBytes()) {
                stemNbt = new byte[stemLen];
                buf.readBytes(stemNbt);
            }
            boolean enabled = buf.readableBytes() >= 1 && buf.readBoolean();
            return new PlayInitPayload(caps, worldSeed, stemNbt, enabled);
        }
    }

    public record ShadowPullRequestWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) { buf.writeVarInt(data.length); buf.writeBytes(data); }
        public static ShadowPullRequestWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) throw new IllegalArgumentException("invalid shadow pull request length");
            byte[] data = new byte[length]; buf.readBytes(data); return new ShadowPullRequestWrapper(data);
        }
    }

    public record ShadowPullResponseWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) { buf.writeVarInt(data.length); buf.writeBytes(data); }
        public static ShadowPullResponseWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            if (length < 0 || length > ShadowPullResponseS2CPacket.MAX_PAYLOAD_BYTES || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid shadow pull response length");
            }
            byte[] data = new byte[length]; buf.readBytes(data); return new ShadowPullResponseWrapper(data);
        }
    }

    public record CompressedPayloadWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static CompressedPayloadWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid CompressedPayloadWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new CompressedPayloadWrapper(data);
        }
    }

    public record AggregationWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static AggregationWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid AggregationWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new AggregationWrapper(data);
        }
    }



    public record SeedRefWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static SeedRefWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid SeedRefWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new SeedRefWrapper(data);
        }
    }

    public record BlockEntityRequestWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static BlockEntityRequestWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid BlockEntityRequestWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new BlockEntityRequestWrapper(data);
        }
    }

    public record BlockEntityDataWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static BlockEntityDataWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid BlockEntityDataWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new BlockEntityDataWrapper(data);
        }
    }


    public record DictionarySyncWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static DictionarySyncWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid DictionarySyncWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new DictionarySyncWrapper(data);
        }
    }

    public record IndexSyncWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static IndexSyncWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid IndexSyncWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new IndexSyncWrapper(data);
        }
    }

    public record CompressionReadyWrapper(boolean ready) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(ready);
        }

        public static CompressionReadyWrapper decode(FriendlyByteBuf buf) {
            return new CompressionReadyWrapper(buf.readBoolean());
        }
    }

    /**
     * 光照增量（S2C）：直连拓扑经 vanilla play 通道下发；
     * 客户端 receiver 解码为 common {@code LightDeltaS2CPacket} 交影子端消费。
     */
    public record LightDeltaWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static LightDeltaWrapper decode(FriendlyByteBuf buf) {
            // length 校验同款（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid LightDeltaWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new LightDeltaWrapper(data);
        }
    }

    private static void sendDictionarySyncPacket(ServerPlayer player) {
        try {
            byte[] aggregationDict = DictionaryManager.getAggregationDict();
            if (aggregationDict == null) {
                aggregationDict = new byte[0];
            }
            DictionarySyncPayload payload = new DictionarySyncPayload(aggregationDict, false);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            payload.encode(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new DictionarySyncWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            sendToPlayer(player, new DictionarySyncWrapper(data));
#endif
            LOGGER.debug("Hassium: Sent dictionary sync packet ({} bytes)", aggregationDict.length);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send dictionary sync packet", e);
        }
    }

    private static void sendIndexSyncPacket(ServerPlayer player) {
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
#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new IndexSyncWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            sendToPlayer(player, new IndexSyncWrapper(data));
#endif
            LOGGER.debug("Hassium: Sent index sync packet ({} bytes)", encoded.length);
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

    // ========== SPI（INetworkManagerService 直连拓扑实现） ==========

    /** 字典同步（服务端调用；Play 期服务端 ZSTD 安装后由 common 握手链经 SPI 下发）。 */
    public static void sendDictionarySync(ServerPlayer player) {
        sendDictionarySyncPacket(player);
    }

    /** 包索引同步（服务端调用）。 */
    public static void sendIndexSync(ServerPlayer player) {
        sendIndexSyncPacket(player);
    }

    /**
     * Play 期激活下发（服务端调用；登录协商完成且玩家 connection 挂载后，
     * common {@code ServerHandshakeActivation} 经 SPI 调用）。
     */
    public static void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                                    byte[] stemNbt, boolean seedGenEnabled) {
        try {
#if MC_VER < MC_1_21_1
            if (CHANNEL != null) {
                CHANNEL.sendTo(new PlayInitPayload(negotiatedCaps, worldSeed, stemNbt, seedGenEnabled),
                        player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
#else
            sendToPlayer(player, new PlayInitPayload(negotiatedCaps, worldSeed, stemNbt, seedGenEnabled));
#endif
            LOGGER.info("Hassium: Sent play init to {} (caps={})", player.getName().getString(),
                    io.github.limuqy.mc.hassium.network.handshake.LoginHandshake.describeCaps(negotiatedCaps));
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send play init to {}", player.getName().getString(), e);
        }
    }

    /** 客户端 compression_ready ACK（C2S；Play 期客户端 ZSTD 安装完成后由 PlayInitClient 经 SPI 调用）。 */
    public static void sendCompressionReady() {
        sendCompressionReadyToServer();
    }
}


