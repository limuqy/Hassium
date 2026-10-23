package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.client.ClientActivation;
import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;

import io.github.limuqy.mc.hassium.protocol.DictionaryManager;
import io.github.limuqy.mc.hassium.protocol.DictionarySnapshot;

import io.github.limuqy.mc.hassium.protocol.PayloadHandlers;
import io.github.limuqy.mc.hassium.protocol.PreHandshakeHelloPayload;
import io.github.limuqy.mc.hassium.protocol.PreHandshakePayload;
import io.github.limuqy.mc.hassium.protocol.PreHandshakeProtocol;
import io.github.limuqy.mc.hassium.protocol.ShadowPullResponseS2CPacket;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.protocol.handshake.PlayInitClient;
import io.github.limuqy.mc.hassium.server.ServerHandshakeActivation;
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
 * {@code play_init_s2c} 激活、{@code aggregation_ready} ACK、字典/索引同步均由 common
 * 握手链（{@code ServerHandshakeActivation} / {@code PlayInitClient}）经 SPI 走本通道，
 * 不再存在网关帧协议 / UDP 数据面 / 续流票据（2.0.0 直连裁剪）。
 */
public class ForgeNetworkManager implements INetworkManagerService {
    public record SleepToClient(byte[] data) {
        static void encode(SleepToClient p, FriendlyByteBuf b) { b.writeByteArray(p.data()); }
        static SleepToClient decode(FriendlyByteBuf b) { return new SleepToClient(b.readByteArray(1024)); }
    }
    public record SleepToServer(byte[] data) {
        static void encode(SleepToServer p, FriendlyByteBuf b) { b.writeByteArray(p.data()); }
        static SleepToServer decode(FriendlyByteBuf b) { return new SleepToServer(b.readByteArray(1024)); }
    }
    @Override public void sendSleepControl(ServerPlayer player, byte[] data) {
#if MC_VER < MC_1_21_1
        player.connection.send(CHANNEL.toVanillaPacket(new SleepToClient(data),NetworkDirection.PLAY_TO_CLIENT));
#else
        throw new UnsupportedOperationException("Sleep requires a validated loader anchor");
#endif
    }
    @Override public void sendSleepControlToServer(byte[] data) {
#if MC_VER < MC_1_21_1
        CHANNEL.sendToServer(new SleepToServer(data));
#else
        throw new UnsupportedOperationException("Sleep requires a validated loader anchor");
#endif
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");
    private static final String PROTOCOL_VERSION = "3";
    private static final int PROTOCOL_VERSION_INT = 3;
    private volatile int aggregationDiscriminator = -1;
    private static volatile java.util.Map<Integer, String> controlTypes = java.util.Map.of();
    private static volatile java.util.Map<String, Integer> controlPrefixes = java.util.Map.of();
    private static void registerControlNames() {
        java.util.Map<Integer, String> types = new java.util.HashMap<>(); java.util.Map<String, Integer> prefixes = new java.util.HashMap<>();
        registerControlName(types, prefixes, new DictionarySyncWrapper(new byte[0]), io.github.limuqy.mc.hassium.protocol.HassiumPacketIds.DICTIONARY_SYNC_S2C);
        registerControlName(types, prefixes, new IndexSyncWrapper(new byte[0]), io.github.limuqy.mc.hassium.protocol.HassiumPacketIds.INDEX_SYNC_S2C);
        registerControlName(types, prefixes, new AggregationWrapper(new byte[0]), io.github.limuqy.mc.hassium.protocol.HassiumPacketIds.AGGREGATION_S2C);
#if MC_VER < MC_1_21_1
        registerControlName(types,prefixes,new SleepToClient(new byte[0]),HassiumChannels.SLEEP_S2C.fullId());
#endif
        controlTypes = java.util.Map.copyOf(types); controlPrefixes = java.util.Map.copyOf(prefixes);
    }
    private static void registerControlName(java.util.Map<Integer, String> types, java.util.Map<String, Integer> prefixes, Object message, String type) {
#if MC_VER < MC_1_21_1
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try { CHANNEL.encodeMessage(message, buf); int index = buf.readerIndex(); int id = buf.readVarInt(); types.put(id, type); prefixes.put(type, buf.readerIndex() - index); }
#else
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try { CHANNEL.encode(buf, message); int index = buf.readerIndex(); int id = buf.readVarInt(); types.put(id, type); prefixes.put(type, buf.readerIndex() - index); }
#endif
        finally { buf.release(); }
    }
    @Override public String egressPacketKey(net.minecraft.network.protocol.Packet<?> packet, io.netty.buffer.ByteBuf encoded, String fallback) {
        if (!HassiumChannels.MAIN_CHANNEL.fullId().equals(fallback)) return fallback;
        FriendlyByteBuf body = new FriendlyByteBuf(encoded.duplicate()); body.readVarInt(); body.readUtf();
        return controlTypes.getOrDefault(body.readVarInt(), fallback);
    }
    @Override public int egressEnvelopePrefixBytes(String type) { return controlPrefixes.getOrDefault(type, 0); }
    private static SimpleChannel fixtureChannel;
    public record FixtureMessage(io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.Payload payload) {
        public void encode(FriendlyByteBuf buf) { io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.write(buf, payload); }
        public static FixtureMessage decode(FriendlyByteBuf buf) { return new FixtureMessage(io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.read(buf)); }
    }
    private static void registerFixture() {
        if (!io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.enabled() || fixtureChannel != null) return;
#if MC_VER < MC_1_21_1
        fixtureChannel = NetworkRegistry.newSimpleChannel(ResourceLocationCompat.vanilla(io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.CHANNEL), () -> "1", "1"::equals, "1"::equals);
        fixtureChannel.registerMessage(0, FixtureMessage.class, FixtureMessage::encode, FixtureMessage::decode,
                (message, context) -> { context.get().setPacketHandled(true); io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.receiveOnClient(message.payload()); }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
#else
        fixtureChannel = ChannelBuilder.named(ResourceLocationCompat.vanilla(io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.CHANNEL))
                .networkProtocolVersion(1).acceptedVersions(Channel.VersionTest.exact(1)).simpleChannel().play().clientbound()
                .addMain(FixtureMessage.class, playCodec(FixtureMessage::encode, FixtureMessage::decode),
                        (message, context) -> io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.receiveOnClient(message.payload()))
                .build();
#endif
    }
    @Override public net.minecraft.network.protocol.Packet<?> createEgressFixtureCarrier(io.github.limuqy.mc.hassium.protocol.egress.EgressFixture.Payload payload) {
        if (fixtureChannel == null) throw new IllegalStateException("Forge fixture channel missing");
#if MC_VER < MC_1_21_1
        return fixtureChannel.toVanillaPacket(new FixtureMessage(payload), NetworkDirection.PLAY_TO_CLIENT);
#else
        return NetworkDirection.PLAY_TO_CLIENT.buildPacket(fixtureChannel, new FixtureMessage(payload));
#endif
    }
    @Override public net.minecraft.network.protocol.Packet<?> createAggregationCarrier(byte[] data) {
#if MC_VER < MC_1_21_1
        return CHANNEL.toVanillaPacket(new AggregationWrapper(data), NetworkDirection.PLAY_TO_CLIENT);
#else
        return NetworkDirection.PLAY_TO_CLIENT.buildPacket(CHANNEL, new AggregationWrapper(data));
#endif
    }
    @Override public void inspectAggregationCarrier(io.netty.buffer.ByteBuf encoded) {
        FriendlyByteBuf body = new FriendlyByteBuf(encoded.duplicate()); body.readVarInt();
        if (!body.readUtf().equals(HassiumChannels.MAIN_CHANNEL.fullId())) throw new IllegalStateException("Unexpected Forge carrier channel");
        aggregationDiscriminator = body.readVarInt();
    }
    @Override public byte[] extractAggregationFrame(String channel, FriendlyByteBuf body) {
        if (!HassiumChannels.MAIN_CHANNEL.fullId().equals(channel)) return null;
        if (aggregationDiscriminator < 0) throw new IllegalStateException("Forge carrier binding missing");
        if (body.readVarInt() != aggregationDiscriminator) return null;
        return INetworkManagerService.super.extractAggregationFrame(io.github.limuqy.mc.hassium.protocol.HassiumPacketIds.AGGREGATION_S2C, body);
    }
    @Override public boolean isEgressControlChannel(String channel) { return channel != null && channel.startsWith("hassium:"); }
    @Override public void sendAggregationReady(Connection connection, int epoch, long id) {
        AggregationReadyWrapper message = new AggregationReadyWrapper(true, epoch, id);
#if MC_VER < MC_1_21_1
        connection.send(CHANNEL.toVanillaPacket(message, NetworkDirection.PLAY_TO_SERVER));
#else
        connection.send(NetworkDirection.PLAY_TO_SERVER.buildPacket(CHANNEL, message));
#endif
    }


#if MC_VER < MC_1_21_1
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocationCompat.vanilla(HassiumChannels.MAIN_CHANNEL),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals)
    );
    private static int packetId = 0;
#else
    /** Forge 50+：在 {@link #registerChannels()} 中构建并赋值 */
    // review-fix: T10-10: commonSetup 主线程赋值、netty 线程读取 → volatile 保证可见性
    public static volatile SimpleChannel CHANNEL;
#endif

    public void registerChannels() {
        // Optional channel definitions must exist independently of chunk takeover/configuration.
        // Runtime handshake/admission gates decide which connection can actually use them.
        LOGGER.debug("Hassium: Registering Forge network channels");
#if MC_VER < MC_1_21_1
        registerLegacyChannels();
#else
        registerModernChannels();
#endif

        registerControlNames();
        registerFixture();
        DictionaryManager.setPushCallback(snapshot -> {
            try {
                net.minecraft.server.MinecraftServer server =
                        net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        sendDictionarySyncPacket(player, snapshot);
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
                    PayloadHandlers.handleAggregation(msg.data());
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

        CHANNEL.<AggregationReadyWrapper>registerMessage(
                packetId++,
                AggregationReadyWrapper.class,
                AggregationReadyWrapper::encode,
                AggregationReadyWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleActivationReadyServer(ctx.get().getSender(), msg));
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
        CHANNEL.<ChunkAuthorityWrapper>registerMessage(
                packetId++, ChunkAuthorityWrapper.class,
                ChunkAuthorityWrapper::encode, ChunkAuthorityWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> PayloadHandlers.handleChunkAuthority(msg.data()));
                    ctx.get().setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(packetId++,SleepToClient.class,SleepToClient::encode,SleepToClient::decode,
                (msg,ctx)-> { var source=ctx.get().getNetworkManager(); ctx.get().enqueueWork(()->io.github.limuqy.mc.hassium.client.sleep.SleepClient.receive(source,msg.data())); ctx.get().setPacketHandled(true); },java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(packetId++,SleepToServer.class,SleepToServer::encode,SleepToServer::decode,
                (msg,ctx)-> { ctx.get().enqueueWork(()->io.github.limuqy.mc.hassium.server.sleep.SleepServer.receive(ctx.get().getSender(),msg.data())); ctx.get().setPacketHandled(true); },java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        LOGGER.info("Hassium: Registered {} network packets", packetId);
    }
#else
    private void registerModernChannels() {
        if (CHANNEL != null) {
            LOGGER.debug("Hassium: Forge channel already registered");
            return;
        }

        SimpleChannel channel = ChannelBuilder
                .named(ResourceLocationCompat.vanilla(HassiumChannels.MAIN_CHANNEL))
                .optional()
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
                        .addMain(AggregationReadyWrapper.class,
                                playCodec(AggregationReadyWrapper::encode, AggregationReadyWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() ->
                                        handleActivationReadyServer(ctx.getSender(), msg)))
                        .addMain(ShadowPullRequestWrapper.class,
                                playCodec(ShadowPullRequestWrapper::encode, ShadowPullRequestWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() -> handleShadowPullRequest(msg, ctx.getSender())))
                    .clientbound()
                        .addMain(AggregationWrapper.class,
                                playCodec(AggregationWrapper::encode, AggregationWrapper::decode),
                                (msg, ctx) -> PayloadHandlers.handleAggregation(msg.data()))
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
                        .addMain(ChunkAuthorityWrapper.class,
                                playCodec(ChunkAuthorityWrapper::encode, ChunkAuthorityWrapper::decode),
                                (msg, ctx) -> ctx.enqueueWork(() ->
                                        PayloadHandlers.handleChunkAuthority(msg.data())))
                .build();
        LOGGER.info("Hassium: Registered Forge 50+ ChannelBuilder play channel (2 C2S + 7 S2C)");
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
        io.github.limuqy.mc.hassium.protocol.PreHandshakeProtocol.handlePreHandshake(playerId, msg, ctx.getConnection());
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
        trySendShadowPullRequest(buf);
    }
    @Override
    public boolean trySendShadowPullRequest(FriendlyByteBuf buf) {
        if (buf == null) {
            return false;
        }
        try {
            io.github.limuqy.mc.hassium.utils.DebugLogger.info(
                    io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.NETWORK,
                    "[DIAG] sendShadowPullRequest called, CHANNEL={}, readable={}", CHANNEL != null ? "set" : "null",
                    buf.readableBytes());
            var minecraft=net.minecraft.client.Minecraft.getInstance();
            var connection=minecraft.getConnection();
            var channel=CHANNEL;
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return PreparedClientSend.trySend(connection,minecraft::getConnection,
                    ()->channel!=null && connection.getConnection().isConnected(),()->{
#if MC_VER < MC_1_21_1
                channel.sendToServer(new ShadowPullRequestWrapper(data));
#else
                channel.send(new ShadowPullRequestWrapper(data),PacketDistributor.SERVER.noArg());
#endif
            },e->LOGGER.warn("Hassium: Failed to send shadowPullV1 request",e));
        } catch (Exception e) {
            LOGGER.warn("Hassium: Failed to send shadowPullV1 request", e);
            return false;
        } finally {
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    // ========== 辅助方法 ==========

    // ========== 共享处理逻辑 ==========

    /**
     * aggregation_ready 服务端 handler：转调 common 激活/重同步链。
     * ready=true → {@code ServerHandshakeActivation.handleActivationReady}
     * （首个 ACK → 字典校验 → 聚合放行；热更期的新版本 ACK → rollout 门控）；
     * ready=false → {@code handleDictionaryResync}（客户端未知 epoch 帧的恢复请求）。
     */
    private static void handleActivationReadyServer(ServerPlayer player, AggregationReadyWrapper msg) {
        if (player == null) {
            return;
        }
        if (!msg.ready()) {
            io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.handleDictionaryResync(player);
            return;
        }
        io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.handleActivationReady(
                player, msg.dictEpoch(), msg.dictId(), msg.hasDictInfo());
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
    private static void sendAggregationReadyToServer(int dictionaryEpoch, long dictionaryId) {
        try {
#if MC_VER < MC_1_21_1
            CHANNEL.sendToServer(new AggregationReadyWrapper(true, dictionaryEpoch, dictionaryId));
#else
            sendToServer(new AggregationReadyWrapper(true, dictionaryEpoch, dictionaryId));
#endif
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send aggregation ready", e);
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
            byte[] response = PayloadHandlers.handleShadowPullRequest(player, msg.data());
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
    public void sendChunkAuthorityS2C(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        if (CHANNEL != null) {
            CHANNEL.sendTo(new ChunkAuthorityWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
#else
        sendToPlayer(player, new ChunkAuthorityWrapper(data));
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

    /** 权威边沿 enter 通知（服务端声明权威集合 + 权威 chunkHash）。 */
    public record ChunkAuthorityWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static ChunkAuthorityWrapper decode(FriendlyByteBuf buf) {
            // length 校验同款（恶意超大 varInt 拒绝分配）
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalArgumentException("invalid ChunkAuthorityWrapper length: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new ChunkAuthorityWrapper(data);
        }
    }

    public record AggregationWrapper(byte[] data) {        public void encode(FriendlyByteBuf buf) {
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

    /**
     * aggregation_ready 载体（ready + 字典回执 epoch/id）。
     * <p>
     * 客户端发送总是带回执（{@link #AggregationReadyWrapper(boolean, int, long)}）；
     * 服务端解码按尾随字段区分旧协议客户端（无回执 → hasDictInfo=false，跳过字典校验）。
     */
    public record AggregationReadyWrapper(boolean ready, int dictEpoch, long dictId, boolean hasDictInfo) {
        public AggregationReadyWrapper(boolean ready) {
            this(ready, 0, 0L, true);
        }

        public AggregationReadyWrapper(boolean ready, int dictEpoch, long dictId) {
            this(ready, dictEpoch, dictId, true);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(ready);
            buf.writeVarInt(dictEpoch);
            buf.writeLong(dictId);
        }

        public static AggregationReadyWrapper decode(FriendlyByteBuf buf) {
            boolean ready = buf.readBoolean();
            int epoch = buf.readVarInt();
            long id = buf.readLong();
            return new AggregationReadyWrapper(ready, epoch, id, true);
        }
    }

    private static void sendDictionarySyncPacket(ServerPlayer player) {
        sendDictionarySyncPacket(player, DictionaryManager.getActiveSnapshot());
    }

    /** 带快照重载：offer 推送在激活版切换前触发，必须携带回调传入的快照（epoch+id+字节）。 */
    private static void sendDictionarySyncPacket(ServerPlayer player, DictionarySnapshot snapshot) {
        try {
            byte[] body = PayloadHandlers.encodeDictionarySyncBody(snapshot);
#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new DictionarySyncWrapper(body), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            sendToPlayer(player, new DictionarySyncWrapper(body));
#endif
            LOGGER.debug("Hassium: Sent dictionary sync packet (epoch={}, {} bytes)",
                    snapshot != null ? snapshot.epoch() : 0, body.length);
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
                             byte[] stemNbt, boolean seedGenEnabled,
                             java.util.List<String> dimensionIds) {
        try {
            LoginHandshake.PlayInitPayload payload =
                    new LoginHandshake.PlayInitPayload(negotiatedCaps, worldSeed, stemNbt,
                            seedGenEnabled, dimensionIds);
#if MC_VER < MC_1_21_1
            if (CHANNEL != null) {
                CHANNEL.sendTo(payload,
                        player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
#else
            sendToPlayer(player, payload);
#endif
            LOGGER.info("Hassium: Sent play init to {} (caps={}, dims={})",
                    player.getName().getString(),
                    io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake.describeCaps(negotiatedCaps),
                    dimensionIds != null ? dimensionIds.size() : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send play init to {}", player.getName().getString(), e);
        }
    }

    /** SPI：客户端 aggregation_ready ACK（C2S；common {@code ClientActivation} 经 Services.NETWORK_MANAGER 消费）。 */
    @Override
    public void sendAggregationReady(int dictionaryEpoch, long dictionaryId) {
        sendAggregationReadyToServer(dictionaryEpoch, dictionaryId);
    }

}
