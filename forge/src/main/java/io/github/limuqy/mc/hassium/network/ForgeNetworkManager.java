package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.network.handshake.PlayInitClient;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
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
public class ForgeNetworkManager implements INetworkManagerService {

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
        // 解包 + 业务分发统一在 common PayloadHandlers（P1b 下沉）；本类只保留传输面。

        CHANNEL.<AggregationWrapper>registerMessage(
                packetId++,
                AggregationWrapper.class,
                AggregationWrapper::encode,
                AggregationWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().setPacketHandled(true);
                    AggregationDecodeQueue.enqueueClient(msg.data());
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.<BlockEntityRequestWrapper>registerMessage(
                packetId++,
                BlockEntityRequestWrapper.class,
                BlockEntityRequestWrapper::encode,
                BlockEntityRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() ->
                            PayloadHandlers.handleBlockEntityRequest(msg.data(), ctx.get().getSender()));
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
                    ctx.get().enqueueWork(() -> PayloadHandlers.handleBlockEntityData(msg.data()));
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
                    ctx.get().enqueueWork(() -> PayloadHandlers.handleDictionarySync(msg.data()));
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
                    ctx.get().enqueueWork(() -> ClientActivation.handleIndexSync(msg.data()));
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
                    ctx.get().enqueueWork(() -> PayloadHandlers.handleShadowPullResponse(msg.data()));
                    ctx.get().setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.<LoginHandshake.PlayInitPayload>registerMessage(
                packetId++, LoginHandshake.PlayInitPayload.class,
                LoginHandshake.PlayInitPayload::encode, LoginHandshake.PlayInitPayload::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> PlayInitClient.handle(msg));
                    ctx.get().setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.<LightDeltaWrapper>registerMessage(
                packetId++, LightDeltaWrapper.class,
                LightDeltaWrapper::encode, LightDeltaWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> PayloadHandlers.handleLightDelta(msg.data()));
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

        // 服务端主导协商（配置阶段任务下发 hello）：客户端在 handler 内同步应答
        // PreHandshakePayload（上面的 CONFIGURATION_TO_SERVER codec）；任务注册见
        // ForgeHandshakeEvents（GatherLoginConfigurationTasksEvent）。
        channel.messageBuilder(PreHandshakeHelloPayload.class, NetworkDirection.CONFIGURATION_TO_CLIENT)
                .codec(PreHandshakeHelloPayload.STREAM_CODEC)
                .consumer(ForgeNetworkManager::onPreHandshakeHello)
                .add();

        CHANNEL = channel
                .play()
                    .serverbound()
                        .addMain(BlockEntityRequestWrapper.class,
                                playCodec(BlockEntityRequestWrapper::encode, BlockEntityRequestWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() ->
                                        PayloadHandlers.handleBlockEntityRequest(msg.data(), ctx.getSender())))
                        .addMain(CompressionReadyWrapper.class,
                                playCodec(CompressionReadyWrapper::encode, CompressionReadyWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() ->
                                        handleActivationReadyServer(ctx.getSender(), msg.ready())))
                        .addMain(ShadowPullRequestWrapper.class,
                                playCodec(ShadowPullRequestWrapper::encode, ShadowPullRequestWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() -> handleShadowPullRequest(msg, ctx.getSender())))
                    .clientbound()
                        .addMain(AggregationWrapper.class,
                                playCodec(AggregationWrapper::encode, AggregationWrapper::decode),
                                (msg, ctx) -> AggregationDecodeQueue.enqueueClient(msg.data()))
                        .addMain(BlockEntityDataWrapper.class,
                                playCodec(BlockEntityDataWrapper::encode, BlockEntityDataWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() ->
                                        PayloadHandlers.handleBlockEntityData(msg.data())))
                        .addMain(ShadowPullResponseWrapper.class,
                                playCodec(ShadowPullResponseWrapper::encode, ShadowPullResponseWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() ->
                                        PayloadHandlers.handleShadowPullResponse(msg.data())))
                        .addMain(DictionarySyncWrapper.class,
                                playCodec(DictionarySyncWrapper::encode, DictionarySyncWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() ->
                                        PayloadHandlers.handleDictionarySync(msg.data())))
                        .addMain(IndexSyncWrapper.class, playCodec(IndexSyncWrapper::encode, IndexSyncWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() -> ClientActivation.handleIndexSync(msg.data())))
                        .addMain(LoginHandshake.PlayInitPayload.class,
                                playCodec(LoginHandshake.PlayInitPayload::encode, LoginHandshake.PlayInitPayload::decode),
                                (msg, ctx) -> ctx.enqueueWork(() -> PlayInitClient.handle(msg)))
                        .addMain(LightDeltaWrapper.class,
                                playCodec(LightDeltaWrapper::encode, LightDeltaWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() -> PayloadHandlers.handleLightDelta(msg.data())))
                .build();
        LOGGER.info("Hassium: Registered Forge 50+ ChannelBuilder play channel (3 C2S + 8 S2C)");
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

    @Override
    public void sendShadowPullRequest(FriendlyByteBuf buf) {
        LOGGER.info("[DIAG] sendShadowPullRequest called, CHANNEL={}, readable={}", CHANNEL != null ? "set" : "null",
                buf == null ? -1 : buf.readableBytes());
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

    // ========== 辅助方法 ==========

    // ========== 共享处理逻辑 ==========

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

#if MC_VER >= MC_1_21_1
    /** 配置阶段握手任务类型（服务端内部标识，不落网络）。 */
    public static final net.minecraft.server.network.ConfigurationTask.Type PRE_HANDSHAKE_TASK_TYPE =
            new net.minecraft.server.network.ConfigurationTask.Type(Constants.MOD_ID + ":pre_handshake");

    /**
     * 服务端下发配置阶段 hello（由 {@code GatherLoginConfigurationTasksEvent} 注册的任务调用）。
     * <p>
     * Forge 自带的 {@code RegisterChannelsTask} 经同一事件注册且排队靠前，任务执行时对端通道
     * 注册已完成，这里按 {@link Channel#isRemotePresent(net.minecraft.network.Connection)} 过滤
     * 原版/异版本客户端（不发起、走原版路径）。消息经 SimpleChannel 发出（{@code ForgePayload}
     * 包装，对端按 {@code hassium:main} 分派 {@code CONFIGURATION_TO_CLIENT} codec）。
     */
    public static void sendPreHandshakeHello(net.minecraft.network.Connection connection) {
        if (connection == null || !connection.isConnected() || CHANNEL == null) {
            return;
        }
        // 无条件发送（对齐 Forge 官方 ModVersionsTask/SyncConfigTask：配置任务阶段对端注册包
        // 尚未到达，remoteChannels/isRemotePresent 恒空，不能作为发送门）。调用方已按
        // ConnectionType.MODDED 过滤；未装 Hassium 的 Forge 客户端按未知 channel 静默忽略。
        CHANNEL.send(PreHandshakeHelloPayload.INSTANCE, connection);
        // 冒烟门禁/排障依赖此行：区分「任务未执行 vs 对端未知 channel 被忽略 vs 正常发起」。
        io.github.limuqy.mc.hassium.Constants.LOG.info("[PRE_HANDSHAKE] hello sent (forge config task)");
    }

    /**
     * 客户端应答配置阶段 hello（C2S 能力声明）。
     * <p>
     * 同步应答（不 enqueueWork）：hello 处理先于 {@code FinishConfiguration} 处理，TCP 全序下
     * 应答先于配置完成 ACK 到达服务端，而 ServerPlayer 在 ACK 后创建——协商登记必然先于
     * {@code ServerHandshakeActivation} 消费。网络开关关闭时不应答（服务端走原版路径）。
     */
    private static void onPreHandshakeHello(PreHandshakeHelloPayload msg, CustomPayloadEvent.Context ctx) {
        ctx.setPacketHandled(true);
        net.minecraft.network.Connection connection = ctx.getConnection();
        if (connection == null || !connection.isConnected() || CHANNEL == null) {
            return;
        }
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            return;
        }
        CHANNEL.send(PreHandshakePayload.create(), connection);
        io.github.limuqy.mc.hassium.Constants.LOG.info("[PRE_HANDSHAKE] announced (answered forge hello)");
    }
#endif
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

    /**
     * shadow_pull_request 传输面：解码 + 权威应答在 common
     * {@link PayloadHandlers#handleShadowPullRequest}，本方法只保留 catch 与
     * {@link ShadowPullResponseWrapper} 回发载体（两版本段共用）。
     */
    private static void handleShadowPullRequest(ShadowPullRequestWrapper msg, ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            byte[] response = PayloadHandlers.handleShadowPullRequest(SHADOW_PULL_HANDLER, player, msg.data());
#if MC_VER < MC_1_21_1
            if (CHANNEL != null) {
                CHANNEL.sendTo(new ShadowPullResponseWrapper(response),
                        player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
#else
            sendToPlayer(player, new ShadowPullResponseWrapper(response));
#endif
        } catch (Exception e) {
            LOGGER.warn("[SERVER] Failed to handle shadowPullV1 request", e);
        }
    }



    // review-fix: T11-14 sendCompressedPayload 退役（common 接口 default no-op，无调用方）


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

    // ========== 数据包记录 ==========

    // play_init S2C 记录已删除：直接注册 common {@link LoginHandshake.PlayInitPayload}
    // （wire 布局与原本地 record 逐字节一致，receiver 转调 PlayInitClient.handle）。

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
            byte[] body = PayloadHandlers.encodeDictionarySyncBody(DictionaryManager.getAggregationDict());
#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new DictionarySyncWrapper(body), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            sendToPlayer(player, new DictionarySyncWrapper(body));
#endif
            LOGGER.debug("Hassium: Sent dictionary sync packet ({} bytes)", body.length);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send dictionary sync packet", e);
        }
    }

    private static void sendIndexSyncPacket(ServerPlayer player) {
        try {
            byte[] envelope = PayloadHandlers.encodeIndexSyncEnvelope();
#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new IndexSyncWrapper(envelope), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            sendToPlayer(player, new IndexSyncWrapper(envelope));
#endif
            LOGGER.debug("Hassium: Sent index sync packet ({} bytes)", envelope.length);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send index sync packet", e);
        }
    }

    // ========== SPI（INetworkManagerService 直连拓扑实现） ==========

    /** 字典同步（服务端调用；Play 期服务端 ZSTD 安装后由 common 握手链经 SPI 下发）。 */
    @Override
    public void sendDictionarySync(ServerPlayer player) {
        sendDictionarySyncPacket(player);
    }

    /** 包索引同步（服务端调用）。 */
    @Override
    public void sendIndexSync(ServerPlayer player) {
        sendIndexSyncPacket(player);
    }

    /**
     * Play 期激活下发（服务端调用；登录协商完成且玩家 connection 挂载后，
     * common {@code ServerHandshakeActivation} 经 SPI 调用）。
     */
    @Override
    public void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                             byte[] stemNbt, boolean seedGenEnabled) {
        try {
#if MC_VER < MC_1_21_1
            if (CHANNEL != null) {
                CHANNEL.sendTo(new LoginHandshake.PlayInitPayload(negotiatedCaps, worldSeed, stemNbt, seedGenEnabled),
                        player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
#else
            sendToPlayer(player, new LoginHandshake.PlayInitPayload(negotiatedCaps, worldSeed, stemNbt, seedGenEnabled));
#endif
            LOGGER.info("Hassium: Sent play init to {} (caps={})", player.getName().getString(),
                    io.github.limuqy.mc.hassium.network.handshake.LoginHandshake.describeCaps(negotiatedCaps));
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send play init to {}", player.getName().getString(), e);
        }
    }

    /** SPI：客户端 compression_ready ACK（C2S；common {@code ClientActivation} 经 Services.NETWORK_MANAGER 消费）。 */
    @Override
    public void sendCompressionReady() {
        sendCompressionReadyToServer();
    }
}


