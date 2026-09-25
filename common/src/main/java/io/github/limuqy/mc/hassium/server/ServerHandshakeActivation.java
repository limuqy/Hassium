package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.protocol.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.protocol.IndexSyncManager;
import io.github.limuqy.mc.hassium.protocol.DictionaryManager;
import io.github.limuqy.mc.hassium.protocol.DictionarySnapshot;
import io.github.limuqy.mc.hassium.protocol.SeedGenTail;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 登录协商结果 → Play 期激活（服务端）。
 * <p>
 * {@code ServerPlayer} 构造完成（{@code <init>} TAIL，connection 尚未挂载）时
 * {@link #onPlayerInit} 消费登录协商位：记录每玩家能力 + 压缩门（自此原版区块窗口
 * 被压制，trackChunk/sendChunk 拦截即生效）；随后每 tick {@link #drainPending} 泵一次，
 * 待 connection 挂载后执行激活序列。
 * <p>
 * <b>激活序列（归拢，单线程 server 线程顺序执行）</b>：聚合协商时初始化字典/索引 →
 * 下发 {@code dictionary_sync}/{@code index_sync} → 连接标 PENDING（聚合缓冲，
 * 5s 无 ACK 降级直发）→ {@code play_init_s2c}（协商结果 + SeedGen 种子）。
 * <p>
 * {@link #handleActivationReady}（客户端收到 index_sync 后的激活 ACK）：
 * PENDING → ENABLED，缓冲的聚合帧冲出。原版压缩层全程不触碰（管线级 ZSTD 已退役，
 * 见 {@code docs/architecture.md}）。
 */
public final class ServerHandshakeActivation {

    /** 每玩家协商位（onPlayerInit 写入，removePlayer 清理）。 */
    private static final Map<UUID, Integer> ACTIVE_CAPS = new ConcurrentHashMap<>();

    /** 激活 ACK 已处理（幂等守卫；重连即新连接新条目）。 */
    private static final Map<UUID, Boolean> READY_HANDLED = new ConcurrentHashMap<>();

    /** 待激活玩家（init 时 connection 未挂载，tick 泵补发）。 */
    private static final Queue<ServerPlayer> PENDING = new ConcurrentLinkedQueue<>();

    private static final ScheduledExecutorService PENDING_TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Hassium-AggregationTimeout");
                t.setDaemon(true);
                return t;
            });

    private ServerHandshakeActivation() {
    }

    /**
     * {@code ServerPlayer <init>} TAIL 调用：消费登录协商位（UUID 键，见
     * {@link PlayerCompressionTracker#consumeNegotiatedCaps}）。
     */
    /** 每玩家 activate 重试次数（connection 晚挂载时回队；超过则放弃并打日志）。 */
    private static final java.util.Map<UUID, Integer> ACTIVATE_RETRIES = new ConcurrentHashMap<>();

    private static final int MAX_ACTIVATE_RETRIES = 100;

    public static void onPlayerInit(ServerPlayer player) {
        // 主机本机（memory）永不走 Hassium 推送抑制；远程 LAN/专用服玩家才消费协商位
        if (io.github.limuqy.mc.hassium.server.ServerNetworkGate.shouldSkipForPlayer(player)) {
            PlayerCompressionTracker.removePlayer(player);
            return;
        }
        int caps = PlayerCompressionTracker.consumeNegotiatedCaps(player);
        if (caps == 0) {
            // requireClientMod 的踢出需要 connection（init 期未挂载），延后到 drainPending
            if (HassiumConfigService.getInstance().isRequireClientMod()) {
                PENDING.add(player);
            }
            return;
        }
        ACTIVE_CAPS.put(player.getUUID(), caps);
        // 压缩门即刻生效：首个 tracking 柱即交给 ServerChunkPushManager（压制原版直推）
        PlayerCompressionTracker.setConnected(player);
        PlayerCompressionTracker.enableCompression(player);
        ACTIVATE_RETRIES.put(player.getUUID(), 0);
        PENDING.add(player);
    }

    /** 该玩家协商位是否含指定能力（未知玩家返回 false）；P2 推送抑制判定用。 */
    public static boolean hasCaps(java.util.UUID playerId, int bit) {
        Integer caps = playerId == null ? null : ACTIVE_CAPS.get(playerId);
        return caps != null && (caps & bit) != 0;
    }

    /**
     * 每 tick 泵（MixinMinecraftServer tickServer TAIL；专用服或 LAN 网络面激活时）。
     */
    public static void drainPending(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        ServerPlayer player;
        while ((player = PENDING.poll()) != null) {
            try {
                activate(player);
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Play activation failed for {}",
                        player.getName().getString(), e);
            }
        }
    }

    private static void activate(ServerPlayer player) {
        if (io.github.limuqy.mc.hassium.server.ServerNetworkGate.shouldSkipForPlayer(player)) {
            Constants.LOG.info("Hassium: [PLAY_INIT] skip activate for {} (network gate)", player.getName().getString());
            return;
        }
        Connection connection = io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
        if (connection == null || !connection.isConnected()) {
            // ServerPlayer <init> 时 connection 常未挂载；回队下 tick 重试，不得丢弃
            int attempt = ACTIVATE_RETRIES.merge(player.getUUID(), 1, Integer::sum);
            if (attempt <= MAX_ACTIVATE_RETRIES) {
                PENDING.add(player);
                if (attempt == 1 || attempt % 20 == 0) {
                    Constants.LOG.debug("Hassium: [PLAY_INIT] connection not ready for {}, requeue attempt {}",
                            player.getName().getString(), attempt);
                }
            } else {
                Constants.LOG.warn("Hassium: [PLAY_INIT] give up activate for {} after {} retries (connection missing)",
                        player.getName().getString(), attempt);
                ACTIVATE_RETRIES.remove(player.getUUID());
            }
            return;
        }
        ACTIVATE_RETRIES.remove(player.getUUID());
        int caps = ACTIVE_CAPS.getOrDefault(player.getUUID(), 0);
        if (caps == 0) {
            if (HassiumConfigService.getInstance().isRequireClientMod()) {
                player.connection.disconnect(Component.literal(
                        "Hassium: this server requires the Hassium client mod (compat.requireClientMod)"));
                Constants.LOG.info("Hassium: Kicked {} (requireClientMod, no login handshake)",
                        player.getName().getString());
            }
            return;
        }
        ServerChunkPushManager push = ServerChunkPushManager.getInstance();
        push.setPlayerLightComputeSupported(player.getUUID(), LoginCaps.has(caps, LoginCaps.LIGHT_STRIP));
        push.setInitialPlayerPosition(player, player.getX(), player.getZ());

        if (LoginCaps.has(caps, LoginCaps.AGGREGATION)) {
            // 激活序列（归拢）：字典/索引先行 → markPending 开启缓冲 → play_init。
            // markPending 必须先于任何可聚合业务流量（isActive 才会接管 Connection.send），
            // 客户端 index_sync ACK（handleActivationReady）前聚合帧不落线。
            DictionaryManager.init(io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerRunDirectory(player));
            IndexSyncManager.getInstance().initializeServerIndex();
            Services.NETWORK_MANAGER.sendDictionarySync(player);
            Services.NETWORK_MANAGER.sendIndexSync(player);
            HassiumConnectionRegistry.markPending(connection);
            HassiumAggregationManager.init();
            DebugLogger.debug(LogType.NETWORK,
                    "Hassium: Marked connection as PENDING for player {}", player.getName().getString());

            String playerName = player.getName().getString();
            PENDING_TIMEOUT_SCHEDULER.schedule(() -> {
                if (HassiumConnectionRegistry.tryDemoteFromPending(connection)) {
                    HassiumAggregationManager.discardConnection(connection);
                    Constants.LOG.warn("Hassium: Ack timeout for {}, disabling aggregation", playerName);
                }
            }, 5, TimeUnit.SECONDS);
        }
        // play_init 下发协商位 + SeedGen 种子（seedGen 未协商/未启用时 seed=0 不泄露）
        sendPlayInit(player, caps);
        Constants.LOG.info("[PLAY_INIT] Activated {} {}",
                player.getName().getString(), LoginHandshake.describeCaps(caps));
    }

    /**
     * 客户端激活 ACK（收到 index_sync 后回发；loader receiver 转调；幂等）。
     * PENDING → ENABLED，把缓冲中的聚合帧冲出（聚合正式放行）。
     * <p>
     * 字典回执（epoch+id，新协议客户端必带）：校验客户端安装的字典与当前激活快照一致才
     * ENABLE；不一致保持 PENDING 并重发字典（5s 超时兜底降级原版路径）。回执同时用于
     * epoch 感知登记与热更 rollout 门控（{@code DictionaryManager.onDictionaryAck}）。
     *
     * @param dictEpoch        客户端已安装字典的 epoch（无回执时为 0）
     * @param dictId           客户端已安装字典的内容 hash（无回执时为 0）
     * @param dictInfoPresent  是否携带字典回执（false = 旧协议客户端）
     */
    public static void handleActivationReady(ServerPlayer player, int dictEpoch, long dictId,
                                             boolean dictInfoPresent) {
        int caps = ACTIVE_CAPS.getOrDefault(player.getUUID(), 0);
        if (!LoginCaps.has(caps, LoginCaps.AGGREGATION)) {
            return;
        }
        Connection connection = io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
        if (connection == null) {
            return;
        }
        // 回执归类（单一判据）：OFFER=热更候选 / ACTIVE=激活版 / NONE=未知
        DictionaryManager.AckKind kind = dictInfoPresent
                ? DictionaryManager.classifyDictionaryAck(dictEpoch, dictId)
                : DictionaryManager.AckKind.NONE;
        if (dictInfoPresent) {
            // 新协议客户端：DICT 帧头写 epoch（顺序在前：ENABLE+flush 冲出的缓冲帧即需带 epoch）
            HassiumConnectionRegistry.markEpochAware(connection);
        }
        if (READY_HANDLED.putIfAbsent(player.getUUID(), Boolean.TRUE) != null) {
            // 后续 ready 只剩热更字典 ACK 语义（rollout 计数）
            if (kind == DictionaryManager.AckKind.OFFER) {
                DictionaryManager.onDictionaryAck(connection, dictEpoch, dictId);
            }
            return;
        }
        if (kind == DictionaryManager.AckKind.NONE && dictInfoPresent) {
            // 客户端安装的字典既不是激活版也不是候选版（同步丢失/损坏）：不 ENABLE
            // （保持 PENDING 缓冲），清幂等守卫允许重装后的 ready 再入，重发当前字典
            Constants.LOG.warn(
                    "Hassium: Dictionary mismatch in activation ready for {} (client epoch={} id={}); resending dictionary, aggregation stays pending",
                    player.getName().getString(), dictEpoch, dictId);
            READY_HANDLED.remove(player.getUUID());
            Services.NETWORK_MANAGER.sendDictionarySync(player);
            DictionaryManager.onConnectionActivated(connection);
            return;
        }
        if (kind == DictionaryManager.AckKind.OFFER) {
            // 客户端在 offer 窗口内入服、装的是候选版：照常计 rollout ACK（可能当场触发切换），
            // 也照常 ENABLE——切换前冲出的缓冲帧是激活版，客户端保留历史可解
            DictionaryManager.onDictionaryAck(connection, dictEpoch, dictId);
        }
        HassiumConnectionRegistry.markEnabled(connection);
        HassiumAggregationManager.flushConnectionSync(connection);
        Constants.LOG.info("Hassium: Aggregation enabled for {} (activation ready{})",
                player.getName().getString(), dictInfoPresent ? ", dict epoch=" + dictEpoch : "");
        // 生命周期收尾：搁置的候选字典（offer 超时放弃等）借新连接激活重试 offer
        DictionaryManager.onConnectionActivated(connection);
    }

    /**
     * 客户端字典重同步请求（{@code aggregation_ready} 的 {@code ready=false}；loader
     * receiver 转调）：客户端解到未知 epoch 的聚合帧后主动请求，服务端重发当前激活字典。
     * <p>
     * 客户端幂等安装后即可恢复当前 epoch 帧解码。历史 epoch 在正常时序下必然存在于客户端
     * 保留窗口（每轮 flip 都要求该连接 ACK 过），缺失只可能来自激活期同步丢失/损坏——
     * 重同步当前版即为完整恢复，无需回原版协议（review P2 恢复策略）。
     */
    public static void handleDictionaryResync(ServerPlayer player) {
        int caps = ACTIVE_CAPS.getOrDefault(player.getUUID(), 0);
        if (!LoginCaps.has(caps, LoginCaps.AGGREGATION)) {
            return;
        }
        Constants.LOG.warn("Hassium: dictionary resync requested by {} (client decoded unknown-epoch frame)",
                player.getName().getString());
        Services.NETWORK_MANAGER.sendDictionarySync(player);
    }

    private static void sendPlayInit(ServerPlayer player, int caps) {
        try {
            net.minecraft.server.level.ServerLevel seedLevel = io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(player);
            boolean enabled = LoginCaps.has(caps, LoginCaps.SEED_GEN)
                    && HassiumConfigService.getInstance().isSeedGenEnabled();
            long worldSeed = SeedGenTail.handshakeWorldSeed(seedLevel, enabled);
            byte[] stemNbt = enabled ? SeedGenTail.encodeLevelStemNbt(seedLevel) : null;
            java.util.List<String> dimensionIds = SeedGenTail.collectDimensionIds(
                    io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player));
            Services.NETWORK_MANAGER.sendPlayInit(player, caps, worldSeed, stemNbt, enabled, dimensionIds);
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Failed to send play init to {}",
                    player.getName().getString(), e);
        }
    }

    /** 玩家断开清理（MixinServerGamePacketListenerImpl.onDisconnect 清理点转调）。 */
    public static void removePlayer(UUID playerId) {
        ACTIVE_CAPS.remove(playerId);
        READY_HANDLED.remove(playerId);
        ACTIVATE_RETRIES.remove(playerId);
    }
}
