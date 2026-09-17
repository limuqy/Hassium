package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps;
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

    /**
     * 单玩家待发 buffer 上限（内存兜底）。
     * <p>
     * 实测不可达：泵每 tick 取走 ≤{@link #MAX_ENTRIES_PER_PACKET} 条，而原版整柱推送 ≤
     * {@code PlayerChunkSender.MAX_CHUNKS_PER_TICK}(64)/tick，稳态待发量在几条到几十条。
     * <p>
     * <b>但溢出本身是「静默丢声明」——与落位点 3x3 空洞同一缺陷类</b>：被丢的柱原版已从
     * {@code pendingChunks} 取走并计了 batch ACK，永不再发 → 客户端再无任何来源。
     * 因此溢出**必须留痕**（{@link #PENDING_OVERFLOW}），不能无声丢弃；旧注释所称
     * 「客户端漏收时靠 self-heal 扫描/pull 补齐」是**错的**（见
     * {@code docs/handoff/handoff-2026-09-13-authority-edge-p5-verdict.md} §9.1）。
     */
    private static final int MAX_PENDING_PER_PLAYER = 8192;
    /** 单包条目上限（与载荷一致）。 */
    private static final int MAX_ENTRIES_PER_PACKET = ChunkAuthorityS2CPacket.MAX_ENTRIES;
    /** 待发缓冲溢出计数（仅诊断）：>0 即声明流有缺口，须立即排查，不是可忽略的限流。 */
    private static final java.util.concurrent.atomic.AtomicLong PENDING_OVERFLOW =
            new java.util.concurrent.atomic.AtomicLong();

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
        /**
         * 本维度内累计「已声明」集合（= 原版 tracking 见过的柱）。
         * <p>
         * 快照 = 把本集合**按当前视距形状**整体重推，于是接收侧任何一次丢失（加入世界窗口期、
         * 丢包）都能在下一个快照点自愈。重推是幂等的：客户端 {@code resolve()} 对已持有柱零动作
         * （{@code clientHolds} 早退 / hash 命中），已在途柱由 {@code markPullInFlight} 去重。
         */
        private final LinkedHashSet<Long> declared = new LinkedHashSet<>();
        /** 加入世界 / 切维后的首次全量重推时刻（0 = 未排定）。 */
        private long resendAtMs;
    }

    /** 加入世界（或切维）后延迟多久做首次全量重推：覆盖「客户端 level 就绪前声明被丢弃」的窗口。 */
    private static final long RESEND_SETTLE_MS = 3_000L;

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
            PlayerState state = STATES.computeIfAbsent(player.getUUID(), ignored -> {
                PlayerState fresh = new PlayerState();
                fresh.resendAtMs = System.currentTimeMillis() + RESEND_SETTLE_MS;
                return fresh;
            });
            synchronized (state) {
                if (!dimension.equals(state.dimension)) {
                    // 切维：客户端集合按维度键存，无需清；epoch 递增仅用于快照语义
                    state.dimension = dimension;
                    state.pending.clear();
                    state.declared.clear(); // 新维度的累计集合从头开始
                    state.resendAtMs = System.currentTimeMillis() + RESEND_SETTLE_MS; // 切维同为「客户端未就绪」窗口
                    state.epoch++;
                    state.snapshotPending = true;
                }
                if (state.pending.size() >= MAX_PENDING_PER_PLAYER) {
                    // 丢掉的是「刚入队」的声明（本方法末尾才 add）——原版已计 ACK 不再重发，
                    // 即永久空洞。留痕而非静默：正常路径下不可达，一旦出现即为真缺陷信号。
                    if (PENDING_OVERFLOW.incrementAndGet() == 1L) {
                        Constants.LOG.warn("Hassium: authority pending overflow (cap={}) - declarations "
                                        + "dropped for {}; those columns may never be delivered",
                                MAX_PENDING_PER_PLAYER, player.getName().getString());
                    }
                    return;
                }
                long packed = ChunkPos.asLong(pos.x, pos.z);
                state.pending.add(packed);
                if (state.declared.size() < MAX_PENDING_PER_PLAYER) {
                    state.declared.add(packed);
                }
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
     * <p>
     * <b>会话首个视距观测点不得作废缓冲。</b>此刻 {@code pending} 里可能正躺着原版首批声明——
     * 首批大小 = {@code PlayerChunkSender.START_CHUNKS_PER_TICK}(9)，按 {@code distanceSquared}
     * 取最近 9 柱，恰好是玩家落位柱为中心的 3x3。原版已把这批柱从 {@code pendingChunks} 取走并计了
     * batch ACK，此后永不再发 → 清空即**永久丢失声明**，客户端再无任何来源交付这 9 柱
     * （实测 {@code 1.21.1_fabric_I_band1/band2} 落位点 3x3 空洞的根因）。
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
            int previous = state.viewDistance;
            state.viewDistance = viewDistance;
            state.epoch++;
            if (previous > 0) {
                // 真实视距变化：把累计声明集合按新形状整体重推（快照语义）。
                requeueDeclared(player, state, viewDistance);
            } else {
                // 会话首个观测点：保留在途缓冲（见方法注释），只标记快照。
                state.snapshotPending = true;
            }
        }
    }

    /**
     * 快照 = 把累计声明集合按当前视距形状整体重推（幂等）。
     * <p>
     * 语义：让位门把「声明流」当作唯一选柱来源，因此**声明不能有不可恢复的丢失**。接收侧存在真实
     * 丢失窗口（{@code ChunkAuthorityClient.handle} 在客户端 level 未就绪时丢弃整包），而原版已把
     * 已推柱移出 {@code pendingChunks}、影子端自绘 pull 又被让位门压住——所以只有重推能自愈。
     * 重推幂等：客户端 {@code resolve()} 对已持有柱零动作，在途柱由 {@code markPullInFlight} 去重。
     * <p>
     * 此处按形状裁剪，既避免把越界柱声明出去（客户端会 pull → 服务端按 range 拒绝），也让累计集合
     * 有界（越界项作废；玩家回到范围内时原版会重新声明）。
     */
    private static void requeueDeclared(ServerPlayer player, PlayerState state, int viewDistance) {
        synchronized (state) {
            ChunkPos center = player.chunkPosition();
            int range = viewDistance + 1; // 与原版玩家 tracking 同参（见 ChunkShapeCompat 文档）
            state.pending.clear();
            var iterator = state.declared.iterator();
            while (iterator.hasNext()) {
                long packed = iterator.next();
                if (io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.contains(
                        center.x, center.z, range, ChunkPos.getX(packed), ChunkPos.getZ(packed))) {
                    state.pending.add(packed);
                } else {
                    iterator.remove();
                }
            }
            state.snapshotPending = true;
        }
    }

    /** 加入世界 / 切维 settle 后做一次全量重推（覆盖「客户端 level 未就绪」丢声明窗口）。 */
    private static void maybeResendDeclared(ServerPlayer player, PlayerState state, long nowMs) {
        int viewDistance;
        synchronized (state) {
            if (state.resendAtMs == 0L || nowMs < state.resendAtMs || state.viewDistance <= 0) {
                return;
            }
            state.resendAtMs = 0L;
            viewDistance = state.viewDistance;
        }
        requeueDeclared(player, state, viewDistance);
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
        long nowMs = System.currentTimeMillis();
        for (ServerPlayer player : players) {
            PlayerState state = STATES.get(player.getUUID());
            if (state == null) {
                continue;
            }
            applyViewDistanceIfChanged(player, state);
            int[] budget = {hashBudget};
            try {
                maybeResendDeclared(player, state, nowMs);
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

    /** 权威声明整族降级（S0）：恒 false，服务端不再发 chunk_authority_s2c。 */
    private static final class PlayerCompressionTrackerGate {
        private static boolean wantsAuthority(ServerPlayer player) {
            return false;
        }
    }
}
