package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneHandshakeAdvertisement;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

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
 * Forge 平台网络管理器实现。
 * <p>
 * 版本整段切分（见 docs/version-segments.md）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_21_1}：旧 SimpleChannel（NetworkRegistry.newSimpleChannel）</li>
 *   <li>{@code MC_VER >= MC_1_21_1}：Forge 50+ ChannelBuilder + play() Payload 风格 SimpleChannel</li>
 * </ul>
 */
public class ForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");
    private static final String PROTOCOL_VERSION = "1";
    private static final int PROTOCOL_VERSION_INT = 1;

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
        // gateway_info S2C 注册必须先于 master.enabled 守卫（与 NeoForge registerPayloads 对齐）：
        // ServerGatewayInfoSender.canSend（dedicated + master.enabled）与注册状态可能脱钩，
        // Forge 侧未注册时 vanilla 直发经 fallback codec 被静默丢弃（DiscardedPayload）。
#if MC_VER >= MC_1_21_1
        ForgeGatewayInfoRegistry.init();
#endif
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: master.enabled=false, skipping Forge channel registration");
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

        CHANNEL.<HandshakePacket>registerMessage(
                packetId++,
                HandshakePacket.class,
                HandshakePacket::encode,
                HandshakePacket::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleHandshakeC2S(msg, ctx.get().getSender(),
                            resp -> CHANNEL.sendTo(resp, ctx.get().getSender().connection.connection,
                                    NetworkDirection.PLAY_TO_CLIENT)));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<HandshakeResponsePacket>registerMessage(
                packetId++,
                HandshakeResponsePacket.class,
                HandshakeResponsePacket::encode,
                HandshakeResponsePacket::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleHandshakeS2C(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

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

        CHANNEL.<DataRequestWrapper>registerMessage(
                packetId++,
                DataRequestWrapper.class,
                DataRequestWrapper::encode,
                DataRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleDataRequest(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<ClientBloomSyncWrapper>registerMessage(
                packetId++,
                ClientBloomSyncWrapper.class,
                ClientBloomSyncWrapper::encode,
                ClientBloomSyncWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleClientBloomSync(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<ChunkHashWrapper>registerMessage(
                packetId++,
                ChunkHashWrapper.class,
                ChunkHashWrapper::encode,
                ChunkHashWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleChunkHash(msg));
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

        CHANNEL.<SectionHashRequestWrapper>registerMessage(
                packetId++,
                SectionHashRequestWrapper.class,
                SectionHashRequestWrapper::encode,
                SectionHashRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleSectionHashRequest(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<SectionDeltaWrapper>registerMessage(
                packetId++,
                SectionDeltaWrapper.class,
                SectionDeltaWrapper::encode,
                SectionDeltaWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleSectionDelta(msg));
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
                    ctx.get().enqueueWork(() -> handleCompressionReadyServer(ctx.get().getSender(), msg.ready()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
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

        // 配置阶段预握手（CONFIGURATION_TO_SERVER）：提前标记 Hassium 客户端，
        // ServerPlayer 创建时自动提升压缩 → 进服第一圈 sendChunk 全走 Hassium 链。
        // 必须在 build() 之前注册：Forge 52.1.15 SimpleChannel.build() 会置 built=true，
        // 之后 messageBuilder 抛 IllegalStateException("SimpleChannel builder is fully built")。
        channel.messageBuilder(PreHandshakePayload.class, NetworkDirection.CONFIGURATION_TO_SERVER)
                .codec(PreHandshakePayload.STREAM_CODEC)
                .consumer(ForgeNetworkManager::onPreHandshake)
                .add();

        CHANNEL = channel
                .play()
                    .serverbound()
                        .addMain(HandshakePacket.class, playCodec(HandshakePacket::encode, HandshakePacket::decode),
                                ForgeNetworkManager::onHandshakeC2S)
                        .addMain(DataRequestWrapper.class, playCodec(DataRequestWrapper::encode, DataRequestWrapper::decode),
                                ForgeNetworkManager::onDataRequest)
                        .addMain(SectionHashRequestWrapper.class,
                                playCodec(SectionHashRequestWrapper::encode, SectionHashRequestWrapper::decode),
                                ForgeNetworkManager::onSectionHashRequest)
                        .addMain(BlockEntityRequestWrapper.class,
                                playCodec(BlockEntityRequestWrapper::encode, BlockEntityRequestWrapper::decode),
                                ForgeNetworkManager::onBlockEntityRequest)
                        .addMain(CompressionReadyWrapper.class,
                                playCodec(CompressionReadyWrapper::encode, CompressionReadyWrapper::decode),
                                ForgeNetworkManager::onCompressionReady)
                        .addMain(ClientBloomSyncWrapper.class,
                                playCodec(ClientBloomSyncWrapper::encode, ClientBloomSyncWrapper::decode),
                                ForgeNetworkManager::onClientBloomSync)
                    .clientbound()
                        .addMain(HandshakeResponsePacket.class,
                                playCodec(HandshakeResponsePacket::encode, HandshakeResponsePacket::decode),
                                ForgeNetworkManager::onHandshakeS2C)
                        .addMain(CompressedPayloadWrapper.class,
                                playCodec(CompressedPayloadWrapper::encode, CompressedPayloadWrapper::decode),
                                ForgeNetworkManager::onCompressedPayload)
                        .addMain(AggregationWrapper.class,
                                playCodec(AggregationWrapper::encode, AggregationWrapper::decode),
                                ForgeNetworkManager::onAggregationClient)
                        .addMain(ChunkHashWrapper.class, playCodec(ChunkHashWrapper::encode, ChunkHashWrapper::decode),
                                ForgeNetworkManager::onChunkHash)
                        .addMain(SectionDeltaWrapper.class, playCodec(SectionDeltaWrapper::encode, SectionDeltaWrapper::decode),
                                ForgeNetworkManager::onSectionDelta)
                        .addMain(BlockEntityDataWrapper.class,
                                playCodec(BlockEntityDataWrapper::encode, BlockEntityDataWrapper::decode),
                                ForgeNetworkManager::onBlockEntityData)
                        .addMain(SeedRefWrapper.class, playCodec(SeedRefWrapper::encode, SeedRefWrapper::decode),
                                ForgeNetworkManager::onSeedRef)
                        .addMain(DictionarySyncWrapper.class,
                                playCodec(DictionarySyncWrapper::encode, DictionarySyncWrapper::decode),
                                ForgeNetworkManager::onDictionarySync)
                        .addMain(IndexSyncWrapper.class, playCodec(IndexSyncWrapper::encode, IndexSyncWrapper::decode),
                                ForgeNetworkManager::onIndexSync)
                .build();

        LOGGER.info("Hassium: Registered Forge 50+ ChannelBuilder play channel (6 C2S + 9 S2C)");
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
        // ServerPlayer 创建时（MixinServerPlayer TAIL）自动提升为压缩启用。
        // 完整协商（ZSTD/聚合/数据面/位置）仍在 Play 阶段 onHandshakeC2S 完成。
        java.util.UUID playerId = null;
        if (ctx.getConnection().getPacketListener()
                instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl configListener) {
            playerId = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileId(configListener.getOwner());
        }
        io.github.limuqy.mc.hassium.network.PreHandshakeProtocol.handlePreHandshake(playerId, msg);
    }

    private static void onHandshakeC2S(HandshakePacket msg, CustomPayloadEvent.Context ctx) {
        // review-fix: T10-M1：consumer 在 netty 线程触发，封送主线程（同 legacy enqueueWork / Fabric server.execute）
        ctx.enqueueWork(() -> handleHandshakeC2S(msg, ctx.getSender(), resp -> CHANNEL.reply(resp, ctx)));
    }

    private static void onHandshakeS2C(HandshakeResponsePacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleHandshakeS2C(msg));
    }

    private static void onCompressedPayload(CompressedPayloadWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleCompressedPayload(msg));
    }

    private static void onAggregationClient(AggregationWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleAggregationClient(msg));
    }

    private static void onDataRequest(DataRequestWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleDataRequest(msg, ctx.getSender()));
    }

    private static void onClientBloomSync(ClientBloomSyncWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleClientBloomSync(msg, ctx.getSender()));
    }

    private static void onChunkHash(ChunkHashWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleChunkHash(msg));
    }

    private static void onSeedRef(SeedRefWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleSeedRef(msg));
    }

    private static void onSectionHashRequest(SectionHashRequestWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleSectionHashRequest(msg, ctx.getSender()));
    }

    private static void onSectionDelta(SectionDeltaWrapper msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> handleSectionDelta(msg));
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
        ctx.enqueueWork(() -> handleCompressionReadyServer(ctx.getSender(), msg.ready()));
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

    /**
     * 通过反射获取 Connection 的 channel 字段
     */
    private static io.netty.channel.Channel getConnectionChannel(Connection connection) {
        try {
            return ZstdPipelineSwitcher.getConnectionChannel(connection);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to get channel from connection", e);
            return null;
        }
    }

    /**
     * 通过反射获取 ServerPlayer 的 Connection
     */
    private static Connection getPlayerConnection(ServerPlayer player) {
        return io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
    }

    // ========== 共享处理逻辑 ==========

    private static void handleHandshakeC2S(
            HandshakePacket msg,
            ServerPlayer player,
            java.util.function.Consumer<HandshakeResponsePacket> reply
    ) {
        if (player == null) {
            LOGGER.error("Hassium: Received handshake from non-player");
            return;
        }

        // 客户端上报位置：校正 resync 视距中心（failover/重连时服务端玩家对象位置滞后）
        // T7 位置上报扩展：完整玩家状态（y/yaw/pitch/维度）
        HandshakeStateTail.C2S stateTail = msg.stateTail();
        PlayerStateReport reportedState = stateTail != null && stateTail.state() != null && stateTail.state().present()
                ? stateTail.state()
                : PlayerStateReport.fromXZ(msg.playerX(), msg.playerZ());
        ServerChunkPushManager.getInstance().setInitialPlayerPosition(player, reportedState);
        // T7 续流验票（验签 + epoch 防重放）→ 续流就绪 → 复用现有推送链
        boolean resumeAccepted = false;
        if (stateTail != null && stateTail.resumeRequested()) {
            ResumeTicketValidator.Verification resume =
                    ResumeTicketValidator.verifyRequest(player.getUUID(), stateTail.resumeTicket());
            if (resume.accepted()) {
                resumeAccepted = true;
                ServerChunkPushManager.getInstance().markPlayerResumeActive(player.getUUID(), resume.epoch());
                LOGGER.info("Hassium: [RESUME] {} ticket verified (epoch={}) — 续流就绪，跳过 login/维度初始化",
                        player.getName().getString(), resume.epoch());
            } else {
                LOGGER.warn("Hassium: [RESUME] {} ticket REJECTED (签名无效/epoch 重放) — 回退完整握手",
                        player.getName().getString());
            }
        }
        // SeedGen 能力记录
        ServerChunkPushManager.getInstance().setPlayerSeedGenSupported(player.getUUID(), msg.seedGenSupported());
        // 光照计算能力记录（剥光协商：false = 不剥光，光随包自带）
        ServerChunkPushManager.getInstance().setPlayerLightComputeSupported(player.getUUID(), msg.lightComputeSupported());

        DebugLogger.debug(LogType.NETWORK,
                "[HANDSHAKE] Received from client {}, protocol={}, globalCompression={}, compactHeader={}",
                player.getName().getString(), msg.protocolVersion(),
                msg.globalPacketCompressionSupported(), msg.compactHeaderSupported());

        PlayerCompressionTracker.enableCompression(player);

        boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
        boolean useGlobalCompression = serverSupportsGlobalCompression && msg.globalPacketCompressionSupported();
        boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
        boolean useCompactHeader = serverSupportsCompactHeader && msg.compactHeaderSupported();

        boolean accepted = true;
        // SeedGen 尾部（append-only；旧客户端忽略尾字节）
        long worldSeed = 0L;
        byte[] seedGenTail = new byte[0];
        boolean seedGenEnabled = HassiumConfigService.getInstance().isSeedGenEnabled();
        try {
            net.minecraft.server.level.ServerLevel seedLevel =
                    io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(player);
            worldSeed = SeedGenTail.handshakeWorldSeed(seedLevel, seedGenEnabled);
            io.netty.buffer.ByteBuf sb = io.netty.buffer.Unpooled.buffer();
            SeedGenTail.writeS2C(new FriendlyByteBuf(sb), seedLevel, seedGenEnabled);
            seedGenTail = new byte[sb.readableBytes()];
            sb.readBytes(seedGenTail);
            sb.release();
        } catch (Throwable e) {
            LOGGER.warn("Hassium: Failed to create Forge seedGen handshake tail", e);
        }
        HandshakeResponsePacket response = new HandshakeResponsePacket(
                Constants.CURRENT_PROTOCOL_VERSION,
                accepted,
                useGlobalCompression,
                useCompactHeader,
                createServerTail(player, msg),
                worldSeed,
                seedGenTail,
                seedGenEnabled,
                resumeAccepted
        );
        // 暂停出站压缩，等客户端 CompressionReady ACK 后再切 ZSTD（与 Fabric/NeoForge 对齐）
        if (useGlobalCompression) {
            DictionaryManager.init();
            IndexSyncManager.getInstance().initializeServerIndex();
            Connection connection = getPlayerConnection(player);
            io.netty.channel.Channel channel = connection != null ? getConnectionChannel(connection) : null;
            if (channel != null) {
                ZstdPipelineSwitcher.pauseOutboundCompression(channel);
            }
        }
        reply.accept(response);
        LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);
        // globalCompression=false 时不会走 CompressionReady→ZSTD 路径，直接补发 chunkHash
        if (accepted && !useGlobalCompression) {
            ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
        }
    }

    private static byte[] createServerTail(ServerPlayer player, HandshakePacket msg) {
        if (msg.dataplaneTail().length == 0) {
            return new byte[0];
        }
        UdpDataPlaneHandshakeTail.C2STail c2s = UdpDataPlaneHandshakeTail.readC2S(
                io.netty.buffer.Unpooled.wrappedBuffer(msg.dataplaneTail()));
        if (!c2s.controlFailoverSupported()) {
            return new byte[0];
        }
        try {
            boolean udpBound = DataPlaneUdpServer.isBound();
            // epoch 口径统一（顺手修）：与 Fabric/NeoForge 一致，取 DataPlaneUdpServer
            // per-player 递增会话 epoch（原 System.nanoTime() 与两端口径不一致）。
            Connection master = getPlayerConnection(player);
            long epoch = master != null
                    ? DataPlaneUdpServer.beginControlConnection(player.getUUID(),
                            () -> master.disconnect(net.minecraft.network.chat.Component.empty()))
                    : System.currentTimeMillis();
            UdpDataPlaneHandshakeTail.S2CTail tail = DataPlaneHandshakeAdvertisement.create(
                    DataPlaneUdpServer.advertisedControlEndpoints(),
                    DataPlaneUdpServer.boundEndpoints(),
                    // D-M1: per-player per-epoch bind token（epoch 变更即轮换；master==null 未签发 → null → 无 UDP 尾）
                    udpBound ? DataPlaneUdpServer.getBindToken(player.getUUID(), epoch) : null,
                    epoch,
                    c2s.udpDataplaneSupported() && udpBound,
                    c2s.controlFailoverSupported());
            io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
            UdpDataPlaneHandshakeTail.writeS2C(buffer, tail);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            buffer.release();
            return data;
        } catch (Throwable e) {
            LOGGER.warn("Hassium: Failed to create Forge failover handshake tail", e);
            return new byte[0];
        }
    }

    private static void handleHandshakeS2C(HandshakeResponsePacket msg) {
        LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}, resumeAccepted={}",
                msg.accepted(), msg.globalCompressionAccepted(), msg.compactHeaderAccepted(), msg.resumeAccepted());
        if (msg.resumeAccepted()) {
            LOGGER.info("Hassium: [RESUME] Server accepted resume — 续流就绪，网关可跳过 login/维度初始化");
        }
        // SeedGen 信息（append-only；旧服务端 worldSeed==0 / seedGenTail 空 → 不启用）
        try {
            if (msg.worldSeed() != 0L && msg.seedGenTail().length > 0) {
                io.netty.buffer.ByteBuf sb = io.netty.buffer.Unpooled.wrappedBuffer(msg.seedGenTail());
                FriendlyByteBuf seedBuf = new FriendlyByteBuf(sb);
                seedBuf.readLong(); // 布局内 worldSeed（与 msg.worldSeed() 相同，跳过）
                long stemLen = seedBuf.readVarInt();
                byte[] stemNbt = null;
                if (stemLen > 0 && stemLen <= seedBuf.readableBytes()) {
                    stemNbt = new byte[(int) stemLen];
                    seedBuf.readBytes(stemNbt);
                }
                boolean seedGenEnabled = seedBuf.readableBytes() >= 1 && seedBuf.readBoolean();
                io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance()
                        .setServerSeedInfo(msg.worldSeed(), stemNbt, seedGenEnabled);
                sb.release();
            }
        } catch (Throwable e) {
            LOGGER.debug("Hassium: failed to decode SeedGen tail (legacy server?)", e);
        }
        if (msg.dataplaneTail().length > 0) {
            try {
                UdpDataPlaneHandshakeTail.S2CTail tail = UdpDataPlaneHandshakeTail.readS2C(
                        io.netty.buffer.Unpooled.wrappedBuffer(msg.dataplaneTail()));
                // UDP 数据面启动：与 fabric 对齐（FabricNetworkManager 同位置 startUdp）。
                // 漏掉则 forge 客户端永不发 BindRequest → 服务端不打 UDP_BIND_OK / UDP_WRR_OK。
                if (tail.hasUdpDataplane()) {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player != null) {
                        java.util.UUID pid = mc.player.getUUID();
                        long epoch = tail.connectionEpoch();
                        mc.execute(() -> {
                            try {
                                io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle
                                        .getInstance().startUdp(pid, epoch, tail);
                            } catch (Throwable t) {
                                LOGGER.warn("Hassium: UDP dataplane start failed", t);
                            }
                        });
                    } else {
                        // 握手早于 player 初始化（PlayerJoin 前）：defer，由 MixinClientTick 主线程续接
                        // （takePendingUdpStart → startUdp + onHandshakeAccepted 补齐；二次调用幂等）。
                        io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle
                                .getInstance().deferUdpStart(tail);
                    }
                }
            } catch (Throwable e) {
                LOGGER.warn("Hassium: Failed to decode Forge failover handshake tail", e);
            }
        }
        if (msg.accepted() && msg.globalCompressionAccepted()) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var conn = mc.getConnection();
            if (conn != null) {
                io.netty.channel.Channel channel = getConnectionChannel(conn.getConnection());
                if (channel != null) {
                    int level = HassiumConfigService.getInstance().getGlobalCompressionLevel();
                    int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
                    // 安装成功后再 markNegotiated + 暂停出站 + ACK：
                    // 服务端仍为 zlib 解码器，客户端大包若立刻 ZSTD 压缩会触发 incorrect header check。
                    // 出站阈值抬到 MAX，Ready/握手窗口内只发未压缩帧；IndexSync 后再恢复阈值。
                    ZstdPipelineSwitcher.switchToZstdWhenReady(channel, threshold, level, () -> {
                        ZstdNegotiationTracker.markNegotiated(channel);
                        ZstdPipelineSwitcher.pauseOutboundCompression(channel);
                        sendCompressionReadyToServer();
                        LOGGER.info("Hassium: Client ZSTD pipeline installed, sent ready ACK (outbound paused)");
                    });
                }
            }
        }
    }

    /**
     * 服务端在收到客户端 ZSTD ready ACK 后安装管线并同步 Dict/Index/chunkHash。
     */
    private static void installServerZstdAfterClientReady(
            ServerPlayer player, Connection connection, io.netty.channel.Channel channel) {
        int level = HassiumConfigService.getInstance().getGlobalCompressionLevel();
        int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
        ZstdPipelineSwitcher.switchToZstdWhenReady(channel, threshold, level, () -> {
            ZstdNegotiationTracker.markNegotiated(channel);
            var server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
            Runnable afterSwitch = () -> {
                sendDictionarySyncPacket(player);
                sendIndexSyncPacket(player);
                ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
                if (connection != null) {
                    HassiumConnectionRegistry.markPending(connection);
                    HassiumAggregationManager.init();
                    schedulePendingTimeout(connection, player.getName().getString());
                }
            };
            if (server != null) {
                server.execute(afterSwitch);
            } else {
                afterSwitch.run();
            }
            LOGGER.info("Hassium: Server ZSTD pipeline installed for {}", player.getName().getString());
        });
    }

    private static void handleCompressionReadyServer(ServerPlayer player, boolean ready) {
        if (!ready || player == null) {
            return;
        }
        Connection connection = getPlayerConnection(player);
        io.netty.channel.Channel channel = connection != null ? getConnectionChannel(connection) : null;
        // 第一次 ready：客户端已装 ZSTD → 服务端切管线并同步 Dict/Index
        if (channel != null && !ZstdPipelineSwitcher.isZstdInstalled(channel)) {
            installServerZstdAfterClientReady(player, connection, channel);
            return;
        }
        // 第二次 ready：IndexSync 已处理 → 启用聚合
        if (connection != null) {
            HassiumConnectionRegistry.markEnabled(connection);
            HassiumAggregationManager.flushConnection(connection);
            LOGGER.debug("Hassium: Marked connection ENABLED for {}", player.getName().getString());
        }
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

    private static void schedulePendingTimeout(Connection connection, String playerName) {
        PENDING_TIMEOUT_SCHEDULER.schedule(() -> {
            if (HassiumConnectionRegistry.tryDemoteFromPending(connection)) {
                HassiumAggregationManager.discardConnection(connection);
                LOGGER.warn("Hassium: Ack timeout for {}, disabling aggregation", playerName);
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
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

    private static void handleDataRequest(DataRequestWrapper msg, ServerPlayer player) {
        try {
            if (player == null) {
                return;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
            ServerChunkPushManager.getInstance()
                    .handleClientChunkDataRequest(player, request);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle chunk data request", e);
        }
    }

    private static void handleClientBloomSync(ClientBloomSyncWrapper msg, ServerPlayer player) {
        try {
            if (player == null) {
                LOGGER.warn("Hassium: Dropped client bloom sync (sender null — PLAY player not ready)");
                return;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            ClientBloomSyncPacket packet = ClientBloomSyncPacket.decode(buf);
            ServerChunkPushManager.getInstance().handleClientBloomSync(player, packet);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle client bloom sync", e);
        }
    }

    private static void handleChunkHash(ChunkHashWrapper msg) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
            ClientMetadataHandler.handleChunkHashPacket(packet);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle chunk hash packet", e);
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

    private static void handleSectionHashRequest(SectionHashRequestWrapper msg, ServerPlayer player) {
        try {
            if (player == null) {
                return;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);
            ServerChunkPushManager.getInstance().handleSectionHashRequest(player, request);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle section hash request", e);
        }
    }

    private static void handleSectionDelta(SectionDeltaWrapper msg) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            try {
                SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submitDelta(packet);
            } finally {
                buf.release();
            }
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle section delta packet", e);
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


    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        // review-fix: T10-7: 未连接时 sendToServer → PacketDistributor.SERVER 抛 IllegalStateException（buf 已 release 丢失）→ 对齐 NeoForge:2555 先查连接
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
#if MC_VER < MC_1_21_1
            CHANNEL.sendToServer(new DataRequestWrapper(data));
#else
            sendToServer(new DataRequestWrapper(data));
#endif
            LOGGER.debug("Hassium: Sent chunk data request");
        } else {
            buf.release();
        }
    }

    // review-fix: T11-14 sendCompressedPayload 退役（common 接口 default no-op，无调用方）

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        CHANNEL.sendTo(new ChunkHashWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        sendToPlayer(player, new ChunkHashWrapper(data));
#endif
    }

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
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        CHANNEL.sendToServer(new SectionHashRequestWrapper(data));
#else
        sendToServer(new SectionHashRequestWrapper(data));
#endif
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        CHANNEL.sendTo(new SectionDeltaWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        sendToPlayer(player, new SectionDeltaWrapper(data));
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
    // 三端一致收口（2026-08-23 裁决）：vanilla 通道 LightDelta 三端客户端均不消费，
    // 唯一消费在网关帧链路；此处仅消费 buf 所有权（release）不再发送。
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        buf.release();
    }

    @Override
    public void sendClientBloomSync(FriendlyByteBuf buf) {
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
#if MC_VER < MC_1_21_1
            CHANNEL.sendToServer(new ClientBloomSyncWrapper(data));
#else
            sendToServer(new ClientBloomSyncWrapper(data));
#endif
            LOGGER.debug("Hassium: Sent client bloom sync");
        } else {
            // 连接不存在，释放缓冲区
            buf.release();
        }
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

    public record HandshakePacket(
            int protocolVersion,
            String modVersion,
            String[] supportedAlgorithms,
            boolean clientCacheSupported,
            boolean chunkRevisionSupported,
            boolean scheme127Supported,
            boolean globalPacketCompressionSupported,
            boolean compactHeaderSupported,
            byte[] dataplaneTail,
            double playerX,
            double playerZ,
            boolean seedGenSupported,
            boolean lightComputeSupported,
            HandshakeStateTail.C2S stateTail
    ) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(protocolVersion);
            buf.writeUtf(modVersion);
            buf.writeVarInt(supportedAlgorithms.length);
            for (String algo : supportedAlgorithms) {
                buf.writeUtf(algo);
            }
            buf.writeBoolean(clientCacheSupported);
            buf.writeBoolean(chunkRevisionSupported);
            buf.writeBoolean(scheme127Supported);
            buf.writeBoolean(globalPacketCompressionSupported);
            buf.writeBoolean(compactHeaderSupported);
            buf.writeVarInt(dataplaneTail.length);
            buf.writeBytes(dataplaneTail);
            buf.writeDouble(playerX);
            buf.writeDouble(playerZ);
            buf.writeBoolean(seedGenSupported);
            buf.writeBoolean(lightComputeSupported);
            // T7 状态尾部（append-only；旧服务端忽略尾字节）
            if (stateTail != null) {
                HandshakeStateTail.writeC2S(buf, stateTail);
            }
        }

        public static HandshakePacket decode(FriendlyByteBuf buf) {
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
            byte[] tail = readTail(buf);
            // 坐标在握手尾部（append-only；旧客户端无此字段）
            double playerX = 0.0;
            double playerZ = 0.0;
            if (buf.isReadable()) {
                try {
                    playerX = buf.readDouble();
                    playerZ = buf.readDouble();
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
            // T7 状态尾部（append-only；旧客户端无此字段 → null）
            HandshakeStateTail.C2S stateTail = null;
            if (buf.isReadable()) {
                stateTail = HandshakeStateTail.readC2S(buf);
            }
            return new HandshakePacket(
                    protocolVersion,
                    modVersion,
                    algorithms,
                    clientCache,
                    chunkRevision,
                    scheme127,
                    globalPacketCompression,
                    compactHeader,
                    tail,
                    playerX,
                    playerZ,
                    seedGenSupported,
                    lightComputeSupported,
                    stateTail
            );
        }
    }

    public record HandshakeResponsePacket(
            int protocolVersion,
            boolean accepted,
            boolean globalCompressionAccepted,
            boolean compactHeaderAccepted,
            byte[] dataplaneTail,
            long worldSeed,
            byte[] seedGenTail,
            boolean seedGenEnabled,
            boolean resumeAccepted
    ) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(protocolVersion);
            buf.writeBoolean(accepted);
            buf.writeBoolean(globalCompressionAccepted);
            buf.writeBoolean(compactHeaderAccepted);
            buf.writeVarInt(dataplaneTail.length);
            buf.writeBytes(dataplaneTail);
            // SeedGen 尾部（append-only；旧客户端忽略尾字节）
            buf.writeLong(worldSeed);
            buf.writeVarInt(seedGenTail.length);
            buf.writeBytes(seedGenTail);
            buf.writeBoolean(seedGenEnabled);
            // T7 续流就绪标记（append-only；旧客户端忽略尾字节）
            buf.writeBoolean(resumeAccepted);
        }

        public static HandshakeResponsePacket decode(FriendlyByteBuf buf) {
            int protocolVersion = buf.readVarInt();
            boolean accepted = buf.readBoolean();
            boolean globalCompressionAccepted = buf.readBoolean();
            boolean compactHeaderAccepted = buf.readBoolean();
            byte[] dataplaneTail = readTail(buf);
            // SeedGen 尾部（append-only；旧服务端无此字段时取默认）
            long worldSeed = 0L;
            byte[] seedGenTail = new byte[0];
            boolean seedGenEnabled = false;
            if (buf.isReadable() && buf.readableBytes() >= 8) {
                try {
                    worldSeed = buf.readLong();
                    int tailLen = buf.readVarInt();
                    if (tailLen > 0 && tailLen <= buf.readableBytes()) {
                        seedGenTail = new byte[tailLen];
                        buf.readBytes(seedGenTail);
                    }
                    if (buf.readableBytes() >= 1) {
                        seedGenEnabled = buf.readBoolean();
                    }
                } catch (Exception ignored) {
                }
            }
            // T7 续流就绪标记（append-only；旧服务端无此字段 → false）
            boolean resumeAccepted = false;
            if (buf.isReadable()) {
                try {
                    resumeAccepted = buf.readBoolean();
                } catch (Exception ignored) {
                }
            }
            return new HandshakeResponsePacket(
                    protocolVersion,
                    accepted,
                    globalCompressionAccepted,
                    compactHeaderAccepted,
                    dataplaneTail,
                    worldSeed,
                    seedGenTail,
                    seedGenEnabled,
                    resumeAccepted
            );
        }
    }

    private static byte[] readTail(FriendlyByteBuf buf) {
        if (!buf.isReadable()) return new byte[0];
        int length = buf.readVarInt();
        if (length < 0 || length > buf.readableBytes()) {
            throw new IllegalArgumentException("invalid handshake tail length: " + length);
        }
        byte[] data = new byte[length];
        buf.readBytes(data);
        return data;
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

    public record DataRequestWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static DataRequestWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid DataRequestWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new DataRequestWrapper(data);
        }
    }

    public record ClientBloomSyncWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static ClientBloomSyncWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid ClientBloomSyncWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new ClientBloomSyncWrapper(data);
        }
    }

    public record ChunkHashWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static ChunkHashWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid ChunkHashWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new ChunkHashWrapper(data);
        }
    }

    public record SectionHashRequestWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static SectionHashRequestWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid SectionHashRequestWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new SectionHashRequestWrapper(data);
        }
    }

    public record SectionDeltaWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static SectionDeltaWrapper decode(FriendlyByteBuf buf) {
            // review-fix: T10-9: length 无上限 → readTail 式校验（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid SectionDeltaWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new SectionDeltaWrapper(data);
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
                // 服务端已装 ZSTD（才能发来 IndexSync），恢复客户端出站压缩阈值
                io.netty.channel.Channel channel = getConnectionChannel(connection);
                if (channel != null) {
                    int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
                    ZstdPipelineSwitcher.setOutboundCompressionThreshold(channel, threshold);
                }
                sendCompressionReadyToServer();
            }
            LOGGER.debug("Hassium: Received index sync ({} types), sent compression ready",
                    clientIndexManager.size());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle index sync", e);
        }
    }
}
