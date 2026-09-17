package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.client.ClientActivation;
import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.PacketId;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import io.github.limuqy.mc.hassium.protocol.AggregationDecodeQueue;
import io.github.limuqy.mc.hassium.protocol.DictionaryManager;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.protocol.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.protocol.PayloadHandlers;
import io.github.limuqy.mc.hassium.protocol.PreHandshakeHelloPayload;
import io.github.limuqy.mc.hassium.protocol.PreHandshakePayload;
import io.github.limuqy.mc.hassium.protocol.PreHandshakeProtocol;
import io.github.limuqy.mc.hassium.protocol.ShadowPullHandler;
import io.github.limuqy.mc.hassium.protocol.ShadowPullRequestLedger;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.protocol.handshake.PlayInitClient;
import io.github.limuqy.mc.hassium.server.ServerHandshakeActivation;

/**
 * NeoForge 平台网络管理器实现。
 * <p>
 * NeoForge ≥1.21.1：Payload + StreamCodec（1.20.1 的 SimpleChannel 兼容线已随 NeoForge 1.20.1 支持退役）。
 * common 聚合能力由 {@link io.github.limuqy.mc.hassium.compat.NetworkCapability} 门控。
 */
public class NeoForgeNetworkManager implements INetworkManagerService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeNetwork");
    private static final String PROTOCOL_VERSION = "1";
    private static final ShadowPullHandler SHADOW_PULL_HANDLER =
            new ShadowPullHandler(new ShadowPullRequestLedger());


    /**
     * 通过反射获取 ServerPlayer 的 Connection
     */
    private static net.minecraft.network.Connection getPlayerConnection(ServerPlayer player) {
        return io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
    }

    // 1.21.1+: 使用 Payload + StreamCodec。
    // byte[] 载荷收敛为单个 ByteArrayPayload（镜像 Fabric RawPayload 模式）：
    // 每通道独立 Type + codec 闭包，线格式与旧 per-channel record 逐字节一致
    // （varint 前缀字节数组）。解包 + 业务分发统一在 common PayloadHandlers。

    /**
     * 通用 byte[] 载荷：type 由 codec 闭包捕获（每通道一个 codec 实例），
     * 发送/接收均按通道 Type 分派。
     */
    public record ByteArrayPayload(Type<ByteArrayPayload> type, byte[] data) implements CustomPacketPayload {
        @Override
        public Type<ByteArrayPayload> type() {
            return type;
        }
    }

    public static final CustomPacketPayload.Type<ByteArrayPayload> SHADOW_PULL_REQUEST_TYPE =
            payloadType(HassiumChannels.SHADOW_PULL_REQUEST_C2S);
    public static final CustomPacketPayload.Type<ByteArrayPayload> SHADOW_PULL_RESPONSE_TYPE =
            payloadType(HassiumChannels.SHADOW_PULL_RESPONSE_S2C);
    public static final CustomPacketPayload.Type<ByteArrayPayload> LIGHT_DELTA_TYPE =
            payloadType(HassiumChannels.LIGHT_DELTA_S2C);
    /** 权威边沿 enter 通知（服务端声明权威集合 + 权威 chunkHash）。 */
    public static final CustomPacketPayload.Type<ByteArrayPayload> CHUNK_AUTHORITY_TYPE =
            payloadType(HassiumChannels.CHUNK_AUTHORITY_S2C);
    public static final CustomPacketPayload.Type<ByteArrayPayload> DICTIONARY_SYNC_TYPE =
            payloadType(HassiumChannels.DICTIONARY_SYNC_S2C);
    public static final CustomPacketPayload.Type<ByteArrayPayload> INDEX_SYNC_TYPE =
            payloadType(HassiumChannels.INDEX_SYNC_S2C);
    public static final CustomPacketPayload.Type<ByteArrayPayload> AGGREGATION_TYPE =
            new CustomPacketPayload.Type<>(ResourceLocationCompat.vanilla(HassiumChannels.AGGREGATION_S2C));

    private static CustomPacketPayload.Type<ByteArrayPayload> payloadType(PacketId id) {
        return new CustomPacketPayload.Type<>(ResourceLocationCompat.vanilla(id));
    }

    private static StreamCodec<FriendlyByteBuf, ByteArrayPayload> codec(
            CustomPacketPayload.Type<ByteArrayPayload> type) {
        return StreamCodec.of(
                (buf, payload) -> buf.writeByteArray(payload.data()),
                buf -> new ByteArrayPayload(type, buf.readByteArray()));
    }

    public record AggregationReadyNeoPayload(boolean ready) implements CustomPacketPayload {
        public static final Type<AggregationReadyNeoPayload> TYPE = new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.AGGREGATION_READY_C2S));
        public static final StreamCodec<FriendlyByteBuf, AggregationReadyNeoPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.ready()),
                buf -> new AggregationReadyNeoPayload(buf.readBoolean())
        );
        @Override
        public Type<AggregationReadyNeoPayload> type() {
            return TYPE;
        }
    }

    /**
     * Play 期激活 Payload (S2C)：登录协商结果 + SeedGen 种子 + 服务端维度清单
     * （线格式 = common {@link LoginHandshake.PlayInitPayload}）。
     */
    public record PlayInitNeoPayload(int negotiatedCaps, long worldSeed, byte[] stemNbt,
                                     boolean seedGenEnabled,
                                     java.util.List<String> dimensionIds)
            implements CustomPacketPayload {
        public static final Type<PlayInitNeoPayload> TYPE =
                new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.PLAY_INIT_S2C));

        public static final StreamCodec<FriendlyByteBuf, PlayInitNeoPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> new LoginHandshake.PlayInitPayload(
                        p.negotiatedCaps(), p.worldSeed(), p.stemNbt(), p.seedGenEnabled(),
                        p.dimensionIds()).encode(buf),
                buf -> {
                    LoginHandshake.PlayInitPayload payload = LoginHandshake.PlayInitPayload.decode(buf);
                    return new PlayInitNeoPayload(payload.negotiatedCaps(), payload.worldSeed(),
                            payload.stemNbt(), payload.seedGenEnabled(), payload.dimensionIds());
                }
        );

        @Override
        public Type<PlayInitNeoPayload> type() {
            return TYPE;
        }
    }


    // ========== 注册方法 ==========

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
        PreHandshakeProtocol.handlePreHandshake(playerId, payload, context.connection());
    }

    // ===== 服务端主导协商（配置阶段任务，NeoForge 官方机制）=====

    /** 配置阶段握手任务类型（服务端内部标识，不落网络）。 */
    private static final net.minecraft.server.network.ConfigurationTask.Type PRE_HANDSHAKE_TASK_TYPE =
            new net.minecraft.server.network.ConfigurationTask.Type(Constants.MOD_ID + ":pre_handshake");

    /**
     * 注册配置阶段握手任务（{@code RegisterConfigurationTasksEvent}，mod bus）。
     * <p>
     * 事件在配置阶段通道协商完成后触发（NeoForge 自身用同一事件注册 CommonVersionTask/
     * CommonRegisterTask 等协商任务，均以 {@code listener.hasChannel(...)} 判定客户端声明），
     * 因此这里按「客户端是否声明 hello 通道」过滤：原版与旧版本客户端不注册任务，零干扰；
     * 任务执行时服务端 payload setup 必已就绪，天然消除「首个配置 tick 发早被踢」竞态。
     */
    @SubscribeEvent
    public static void onRegisterConfigurationTasks(RegisterConfigurationTasksEvent event) {
        if (!io.github.limuqy.mc.hassium.server.ServerNetworkGate.isNetworkServerActive()) {
            return;
        }
        net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener listener = event.getListener();
        if (!listener.hasChannel(PreHandshakeHelloPayload.TYPE)) {
            return;
        }
        event.register(new PreHandshakeTask(listener));
    }

    /**
     * 配置阶段握手任务：下发 hello 后立即完成（fire-and-forget，不等应答）。
     * <p>
     * 时序保证：TCP 全序下客户端在 hello handler 内同步应答 C2S 能力声明，该应答先于
     * {@code FinishConfiguration} 的客户端 ACK 到达服务端，而 ServerPlayer 在 ACK 之后创建
     * ——协商结果必然先于 {@code ServerHandshakeActivation} 消费。客户端不应答时协商不登记，
     * 走原版路径（不 stall 登录，故不采用官方 wait-ack 形态）。
     */
    private static final class PreHandshakeTask implements ICustomConfigurationTask {

        private final net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener listener;

        private PreHandshakeTask(
                net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener listener) {
            this.listener = listener;
        }

        @Override
        public void run(java.util.function.Consumer<CustomPacketPayload> sender) {
            sender.accept(PreHandshakeHelloPayload.INSTANCE);
            ((net.neoforged.neoforge.common.extensions.IServerConfigurationPacketListenerExtension) listener)
                    .finishCurrentTask(PRE_HANDSHAKE_TASK_TYPE);
        }

        @Override
        public net.minecraft.server.network.ConfigurationTask.Type type() {
            return PRE_HANDSHAKE_TASK_TYPE;
        }
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // play_init S2C 必须无条件注册（先于下方守卫）：登录协商位非零即下发 play_init
        //（即便 net/master 全关，SHADOW_PULL 位恒协商成功）——NeoForge checkPacket 对未注册
        // S2C payload 直接抛异常炸 tick。服务端不发送时注册无副作用。
        var registrar = event.registrar(PROTOCOL_VERSION);

        // 聚合帧发送器：fabric 在 registerChannels 设置；neoforge 无对应调用点，
        // 在 payload 注册事件（服务端/客户端都触发，sender 仅服务端连接生效）设置。
        io.github.limuqy.mc.hassium.protocol.HassiumAggregationManager.setSender((connection, buf) -> {
            if (connection.getPacketListener() instanceof net.minecraft.server.network.ServerGamePacketListenerImpl handler) {
                ServerPlayer player = handler.getPlayer();
                sendServerPayload(player, new ByteArrayPayload(AGGREGATION_TYPE, PayloadHandlers.drain(buf)));
            } else {
                LOGGER.error("Cannot send aggregation packet: connection has no player");
                buf.release();
            }
        });

        // 字典热推回调（镜像 fabric/forge）：服务端字典重建后向全体在线玩家推送。
        DictionaryManager.setPushCallback(dictionary -> {
            try {
                net.minecraft.server.MinecraftServer server =
                        net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        sendDictionarySyncPacket(player);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to push dictionary to clients", e);
            }
        });

        registrar.playToClient(
                PlayInitNeoPayload.TYPE,
                PlayInitNeoPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        PlayInitClient.handle(new LoginHandshake.PlayInitPayload(
                                payload.negotiatedCaps(), payload.worldSeed(),
                                payload.stemNbt(), payload.seedGenEnabled(),
                                payload.dimensionIds())))
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

        // 服务端主导协商（配置阶段任务下发 hello）：客户端在 handler 内同步应答
        // PreHandshakePayload（上面的 C2S 通道）；任务注册见 onRegisterConfigurationTasks。
        registrar.configurationToClient(
                PreHandshakeHelloPayload.TYPE,
                PreHandshakeHelloPayload.STREAM_CODEC,
                (payload, context) -> context.reply(PreHandshakePayload.create())
        );


        registrar.playToServer(SHADOW_PULL_REQUEST_TYPE, codec(SHADOW_PULL_REQUEST_TYPE),
                NeoForgeNetworkManager::handleShadowPullRequest);
        registrar.playToClient(SHADOW_PULL_RESPONSE_TYPE, codec(SHADOW_PULL_RESPONSE_TYPE),
                NeoForgeNetworkManager::handleShadowPullResponse);


        registrar.playToServer(
                AggregationReadyNeoPayload.TYPE,
                AggregationReadyNeoPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player && payload.ready()) {
                        // 直连拓扑：转调 common 激活链（服务端 ZSTD 切换 + Dict/Index 同步 + 聚合放行）
                        ServerHandshakeActivation.handleActivationReady(player);
                    }
                })
        );

        // ===== S2C（客户端处理；与服务端发送方向一一对应）=====

        // LightDelta S2C（直连拓扑：客户端影子端经 vanilla 通道消费）
        registrar.playToClient(LIGHT_DELTA_TYPE, codec(LIGHT_DELTA_TYPE),
                (payload, ctx) -> PayloadHandlers.handleLightDelta(payload.data()));

        // 权威边沿 enter S2C：客户端三分支解析（hash 命中 → 零请求本地交付 + 记缓存全命中）
        registrar.playToClient(CHUNK_AUTHORITY_TYPE, codec(CHUNK_AUTHORITY_TYPE),
                (payload, ctx) -> ctx.enqueueWork(() -> PayloadHandlers.handleChunkAuthority(payload.data())));

        // 字典同步 S2C
        registrar.playToClient(DICTIONARY_SYNC_TYPE, codec(DICTIONARY_SYNC_TYPE),
                (payload, ctx) -> ctx.enqueueWork(() -> PayloadHandlers.handleDictionarySync(payload.data())));

        // 索引同步 S2C
        registrar.playToClient(INDEX_SYNC_TYPE, codec(INDEX_SYNC_TYPE),
                (payload, ctx) -> ctx.enqueueWork(() -> ClientActivation.handleIndexSync(payload.data())));

        // 聚合帧 S2C（客户端影子端 decode 统计 zstd/vanilla 流量锚点）
        registrar.playToClient(AGGREGATION_TYPE, codec(AGGREGATION_TYPE),
                (payload, ctx) -> AggregationDecodeQueue.enqueueClient(payload.data()));

        LOGGER.info("Hassium: Registered all NeoForge payload handlers");
    }

    private static void handleShadowPullRequest(ByteArrayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            try {
                // 解码 + 权威 Compare+Pull 应答编码在 common PayloadHandlers；本端只保留 catch 与发送载体。
                byte[] response = PayloadHandlers.handleShadowPullRequest(SHADOW_PULL_HANDLER, player, payload.data());
                sendServerPayload(player, new ByteArrayPayload(SHADOW_PULL_RESPONSE_TYPE, response));
            } catch (Exception e) {
                LOGGER.warn("[SERVER] Failed to handle shadowPullV1 request", e);
            }
        });
    }

    private static void handleShadowPullResponse(ByteArrayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> PayloadHandlers.handleShadowPullResponse(payload.data()));
    }

    // ===== S2C 客户端处理 =====


    /** NeoForge payload 发送必须经服务端主线程，避免异步推送批次丢失。 */
    private static void sendServerPayload(ServerPlayer player, CustomPacketPayload payload) {
        net.minecraft.server.MinecraftServer server =
                io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
        if (server != null) {
            server.execute(() -> player.connection.send(payload));
        }
    }

    /**
     * 客户端 shadowPull 请求（C2S）。fabric 走 FabricSendCompat；本端必须经
     * play connection 直发 ByteArrayPayload——默认 SPI 实现是 no-op，
     * 漏实现会让整个 Compare+Pull 静默失效（请求发出但永远不落线）。
     */
    @Override
    public void sendShadowPullRequest(FriendlyByteBuf buf) {
        if (buf == null) {
            return;
        }
        try {
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (connection != null && buf.isReadable()) {
                byte[] data = PayloadHandlers.drain(buf);
                connection.send(new ByteArrayPayload(SHADOW_PULL_REQUEST_TYPE, data));
            }
        } catch (Exception e) {
            LOGGER.warn("Hassium: Failed to send shadowPullV1 request", e);
        } finally {
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    @Override
    public void sendShadowPullResponse(ServerPlayer player, FriendlyByteBuf buf) {
        sendServerPayload(player, new ByteArrayPayload(SHADOW_PULL_RESPONSE_TYPE, PayloadHandlers.drain(buf)));
    }

    @Override
    public void sendChunkAuthorityS2C(ServerPlayer player, FriendlyByteBuf buf) {
        sendServerPayload(player, new ByteArrayPayload(CHUNK_AUTHORITY_TYPE, PayloadHandlers.drain(buf)));
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // 直连拓扑：网关帧链路（LIGHT_DELTA 原唯一消费方）已裁剪，改经 vanilla play S2C payload
        // 下发，客户端影子端 ShadowLightCompute 直连消费（任意线程安全）。
        sendServerPayload(player, new ByteArrayPayload(LIGHT_DELTA_TYPE, PayloadHandlers.drain(buf)));
    }

    /**
     * 发送 Play 期激活包（协商结果 + SeedGen 种子 + 维度清单；直连拓扑 S2C payload）。
     */
    @Override
    public void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                             byte[] stemNbt, boolean seedGenEnabled,
                             java.util.List<String> dimensionIds) {
        try {
            sendServerPayload(player, new PlayInitNeoPayload(
                    negotiatedCaps, worldSeed, stemNbt, seedGenEnabled, dimensionIds));
            LOGGER.debug("Hassium: Sent play init to {} (caps={}, dims={})",
                    player.getName().getString(), LoginHandshake.describeCaps(negotiatedCaps),
                    dimensionIds != null ? dimensionIds.size() : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send play init packet", e);
        }
    }


    /** SPI：发送聚合字典同步到客户端（转调静态实现；push 回调同用）。 */
    @Override
    public void sendDictionarySync(ServerPlayer player) {
        sendDictionarySyncPacket(player);
    }

    /** SPI：发送包索引同步到客户端（转调静态实现）。 */
    @Override
    public void sendIndexSync(ServerPlayer player) {
        sendIndexSyncPacket(player);
    }

    /** 发送聚合字典同步到客户端（SPI：Services.NETWORK_MANAGER.sendDictionarySync 转调）。 */
    public static void sendDictionarySyncPacket(ServerPlayer player) {
        try {
            byte[] body = PayloadHandlers.encodeDictionarySyncBody(DictionaryManager.getAggregationDict());
            player.connection.send(new ByteArrayPayload(DICTIONARY_SYNC_TYPE, body));
            LOGGER.debug("Hassium: Sent dictionary sync ({} bytes) to {}", body.length, player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send dictionary sync packet", e);
        }
    }

    /** 发送包索引同步到客户端（SPI：Services.NETWORK_MANAGER.sendIndexSync 转调）。 */
    public static void sendIndexSyncPacket(ServerPlayer player) {
        try {
            byte[] envelope = PayloadHandlers.encodeIndexSyncEnvelope();
            player.connection.send(new ByteArrayPayload(INDEX_SYNC_TYPE, envelope));
            LOGGER.debug("Hassium: Sent index sync to {}", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send index sync packet", e);
        }
    }

    /** SPI：客户端 aggregation_ready ACK（C2S；common {@code ClientActivation} 经 Services.NETWORK_MANAGER 消费）。 */
    @Override
    public void sendAggregationReady() {
        sendAggregationReadyToServer();
    }

    public static void sendAggregationReadyToServer() {
        try {
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(new AggregationReadyNeoPayload(true));
            }
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send aggregation ready", e);
        }
    }

}


