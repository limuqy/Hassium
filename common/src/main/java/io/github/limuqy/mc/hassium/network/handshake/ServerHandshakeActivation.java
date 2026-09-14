package io.github.limuqy.mc.hassium.network.handshake;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.network.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.network.IndexSyncManager;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.SeedGenTail;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
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
    public static void onPlayerInit(ServerPlayer player) {
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
        PENDING.add(player);
    }

    /** 该玩家协商位是否含指定能力（未知玩家返回 false）；P2 推送抑制判定用。 */
    public static boolean hasCaps(java.util.UUID playerId, int bit) {
        Integer caps = playerId == null ? null : ACTIVE_CAPS.get(playerId);
        return caps != null && (caps & bit) != 0;
    }

    /**
     * 每 tick 泵（MixinMinecraftServer tickServer TAIL；仅专用服）。
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
        Connection connection = io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
        if (connection == null || !connection.isConnected()) {
            return;
        }
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
        DebugLogger.info(LogType.NETWORK, "[PLAY_INIT] Activated {} {}",
                player.getName().getString(), LoginHandshake.describeCaps(caps));
    }

    /**
     * 客户端激活 ACK（收到 index_sync 后回发；loader receiver 转调；幂等）。
     * PENDING → ENABLED，把缓冲中的聚合帧冲出（聚合正式放行）。
     */
    public static void handleActivationReady(ServerPlayer player) {
        int caps = ACTIVE_CAPS.getOrDefault(player.getUUID(), 0);
        if (!LoginCaps.has(caps, LoginCaps.AGGREGATION)) {
            return;
        }
        Connection connection = io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
        if (connection == null) {
            return;
        }
        if (READY_HANDLED.putIfAbsent(player.getUUID(), Boolean.TRUE) != null) {
            return;
        }
        HassiumConnectionRegistry.markEnabled(connection);
        HassiumAggregationManager.flushConnectionSync(connection);
        Constants.LOG.info("Hassium: Aggregation enabled for {} (activation ready)",
                player.getName().getString());
    }

    private static void sendPlayInit(ServerPlayer player, int caps) {
        try {
            net.minecraft.server.level.ServerLevel seedLevel = io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(player);
            boolean enabled = LoginCaps.has(caps, LoginCaps.SEED_GEN)
                    && HassiumConfigService.getInstance().isSeedGenEnabled();
            long worldSeed = SeedGenTail.handshakeWorldSeed(seedLevel, enabled);
            byte[] stemNbt = enabled ? SeedGenTail.encodeLevelStemNbt(seedLevel) : null;
            Services.NETWORK_MANAGER.sendPlayInit(player, caps, worldSeed, stemNbt, enabled);
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Failed to send play init to {}",
                    player.getName().getString(), e);
        }
    }

    /** 玩家断开清理（MixinServerGamePacketListenerImpl.onDisconnect 清理点转调）。 */
    public static void removePlayer(UUID playerId) {
        ACTIVE_CAPS.remove(playerId);
        READY_HANDLED.remove(playerId);
    }
}
