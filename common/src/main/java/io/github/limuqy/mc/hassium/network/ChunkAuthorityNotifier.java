package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation;
import io.github.limuqy.mc.hassium.platform.Services;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 权威边沿声明器（服务端 → 客户端）：把「柱进入真实玩家权威 tracking 集合」与
 * 权威内容 hash 一并告知客户端，替代「客户端影子端自绘几何推断权威集合」。
 * <p>
 * 发射点 = 原版整柱推送的抑制点（1.20.1 {@code ServerPlayer.trackChunk} /
 * 1.21.1+ {@code PlayerChunkSender.sendChunk}）；leave 继续由原版 Forget 承载，
 * 本类不产生 leave 载荷。
 * <p>
 * hash 策略（详见 {@link ChunkAuthorityHashes}）：缓存命中直接附带；未命中在
 * <b>每 tick 预算内</b>现算并写入；预算不足或柱未加载 → 附带 0（未知），客户端照常 pull。
 * 因此本通知是纯优化，永不阻断交付。
 */
public final class ChunkAuthorityNotifier {

    /** 单玩家待发 buffer 上限：超出丢弃最旧（客户端漏收时靠 self-heal 扫描/pull 补齐）。 */
    private static final int MAX_PENDING_PER_PLAYER = 8192;
    /** 单包条目上限（与载荷一致）。 */
    private static final int MAX_ENTRIES_PER_PACKET = ChunkAuthorityS2CPacket.MAX_ENTRIES;

    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

    private ChunkAuthorityNotifier() {
    }

    private static final class PlayerState {
        private String dimension;
        private long epoch = 1L;
        /** 上次观察到的玩家视距；变化即 epoch 递增（客户端见快照先清空集合）。 */
        private int viewDistance = -1;
        /** 待发 enter（按插入序，去重）。 */
        private final LinkedHashSet<Long> pending = new LinkedHashSet<>();
        /** 下一次发包是否标记全量快照（epoch 变更后首包）。 */
        private boolean snapshotPending;
    }

    /**
     * 柱进入权威集合（抑制点调用）。
     * <p>
     * 链路可用性门控与原版推送抑制一致：仅对已启用压缩且协商了 PULL_MODE +
     * AUTHORITY_NOTIFY 的玩家发通知；否则完全不出包（走原路径）。
     */
    public static void onAuthoritativeEnter(ServerPlayer player, ServerLevel level, ChunkPos pos) {
        if (player == null || level == null || pos == null) {
            return;
        }
        try {
            if (!PlayerCompressionTrackerGate.wantsAuthority(player)) {
                return;
            }
            String dimension = LevelCompat.getDimensionId(level);
            if (dimension == null) {
                return;
            }
            PlayerState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
            synchronized (state) {
                if (!dimension.equals(state.dimension)) {
                    // 切维：客户端集合按维度键存，无需清；epoch 递增仅用于快照语义
                    state.dimension = dimension;
                    state.pending.clear();
                    state.epoch++;
                    state.snapshotPending = true;
                }
                if (state.pending.size() >= MAX_PENDING_PER_PLAYER) {
                    return;
                }
                state.pending.add(ChunkPos.asLong(pos.x, pos.z));
            }
        } catch (Throwable t) {
            Constants.LOG.debug("Hassium: authority enter notify skipped ({}, {})", pos.x, pos.z, t);
        }
    }

    /**
     * 视距变更（每 tick 自检，无需外部钩子）：epoch 递增 + 下一包标记快照。
     * <p>
     * 客户端收到快照先清空本地集合；原版 {@code ChunkMap.setViewDistance} 自带全 holder
     * 重跟踪，因此新的 tracking 集合会通过抑制点重新灌入 enter，无需服务端枚举集合。
     */
    private static void applyViewDistanceIfChanged(ServerPlayer player, PlayerState state) {
        int viewDistance = io.github.limuqy.mc.hassium.compat.PlayerCompat.getViewDistance(player);
        if (viewDistance <= 0) {
            return;
        }
        synchronized (state) {
            if (state.viewDistance == viewDistance) {
                return;
            }
            state.viewDistance = viewDistance;
            state.epoch++;
            state.pending.clear();
            state.snapshotPending = true;
        }
    }

    /** 每 tick 泵：每玩家一包（受条目上限约束），hash 现算受 {@code hashBudget} 约束。 */
    public static void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (STATES.isEmpty() || server == null) {
            return;
        }
        int configured = HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick();
        // 权威 hash 预算与 pull 分类解耦：P2 让位后 pull 侧预算大量闲置，而本通知按
        // 「每 tick 一包（≤128 条）」下发，需要与之匹配的现算额度；不足则附 0（best-effort，
        // 客户端照常 pull）。首轮过后全部走缓存命中，不再消耗现算。
        int hashBudget = Math.max(32, configured * 8);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (STATES.size() > players.size()) {
            java.util.Set<UUID> online = new java.util.HashSet<>();
            for (ServerPlayer online_player : players) {
                online.add(online_player.getUUID());
            }
            STATES.keySet().removeIf(id -> !online.contains(id));
        }
        for (ServerPlayer player : players) {
            PlayerState state = STATES.get(player.getUUID());
            if (state == null) {
                continue;
            }
            applyViewDistanceIfChanged(player, state);
            int[] budget = {hashBudget};
            try {
                pumpPlayer(player, state, budget);
            } catch (Throwable t) {
                Constants.LOG.debug("Hassium: authority pump failed for {}", player.getUUID(), t);
            }
        }
    }

    private static void pumpPlayer(ServerPlayer player, PlayerState state, int[] hashBudget) {
        List<ChunkAuthorityS2CPacket.Entry> entries = new ArrayList<>(MAX_ENTRIES_PER_PACKET);
        String dimension;
        long epoch;
        boolean snapshot;
        synchronized (state) {
            dimension = state.dimension;
            if (dimension == null || state.pending.isEmpty()) {
                return;
            }
            epoch = state.epoch;
            snapshot = state.snapshotPending;
            var iterator = state.pending.iterator();
            while (iterator.hasNext() && entries.size() < MAX_ENTRIES_PER_PACKET) {
                long packed = iterator.next();
                iterator.remove();
                int x = ChunkPos.getX(packed);
                int z = ChunkPos.getZ(packed);
                entries.add(new ChunkAuthorityS2CPacket.Entry(x, z, hashFor(player, x, z, hashBudget)));
            }
            if (snapshot) {
                state.snapshotPending = false;
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        int hashed = 0;
        for (ChunkAuthorityS2CPacket.Entry entry : entries) {
            if (entry.hash() != 0L) {
                hashed++;
            }
        }
        io.github.limuqy.mc.hassium.utils.DebugLogger.info(
                io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.NETWORK,
                "[AUTHORITY] send dim={} entries={} hashed={} snapshot={} epoch={}",
                dimension, entries.size(), hashed, snapshot, epoch);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new ChunkAuthorityS2CPacket(dimension, epoch, snapshot, entries).encode(buf);
        Services.NETWORK_MANAGER.sendChunkAuthorityS2C(player, buf);
    }

    /** 权威内容 hash：缓存命中直发；未命中在预算内现算落缓存；否则 0（未知）。 */
    private static long hashFor(ServerPlayer player, int chunkX, int chunkZ, int[] hashBudget) {
        ServerLevel level = io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(player);
        if (level == null) {
            return 0L;
        }
        String dimension = LevelCompat.getDimensionId(level);
        if (dimension == null) {
            return 0L;
        }
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        Long cached = ChunkAuthorityHashes.get(dimension, pos);
        if (cached != null) {
            return cached;
        }
        if (hashBudget[0] <= 0) {
            return 0L; // 预算耗尽：本柱不附带 hash，客户端按未知处理
        }
        LevelChunk chunk = LevelCompat.loadedFullChunk(level, chunkX, chunkZ);
        if (chunk == null) {
            return 0L;
        }
        hashBudget[0]--;
        long hash = ChunkContentHashUtil.combineSectionHashes(
                ChunkContentHashUtil.computeSectionHashes(chunk));
        ChunkAuthorityHashes.put(dimension, pos, hash);
        return hash;
    }

    /** 链路门控：压缩启用 + PULL_MODE + AUTHORITY_NOTIFY 协商位。 */
    private static final class PlayerCompressionTrackerGate {
        private static boolean wantsAuthority(ServerPlayer player) {
            if (!io.github.limuqy.mc.hassium.network.PlayerCompressionTracker
                    .isCompressionEnabled(player)) {
                return false;
            }
            UUID id = player.getUUID();
            return ServerHandshakeActivation.hasCaps(id, LoginCaps.PULL_MODE)
                    && ServerHandshakeActivation.hasCaps(id, LoginCaps.AUTHORITY_NOTIFY);
        }
    }
}
