package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;
import io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.compression.CompressionException;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaPlanner;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshot;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import io.github.limuqy.mc.hassium.mixin.LevelChunkWithLightPacketAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 服务端区块推送管理器
 * <p>
 * 纯 Compare+Pull：权威比较、待推送队列泵、FULL/DELTA 后台 encode+zstd。
 * 原版 tracking 整柱推送由 mixin 在 pull 模式拦截；无直推队列。
 */
public class ServerChunkPushManager {

    private static final ServerChunkPushManager INSTANCE = new ServerChunkPushManager();

    /**
     * 每玩家光照计算能力（握手 C2S 上报 lightComputeSupported = 客户端 chunk.enabled）。
     * 服务端据此决定是否剥光：客户端声明可本地/影子端算光才剥（stripLightIfConfigured gate）。
     */
    private final Map<UUID, Boolean> playerLightComputeSupported = new ConcurrentHashMap<>();

    /** pull FULL 载荷固定 zstd；失败返回 null（调用方按 encode 失败回 ERROR）。 */
    private static byte[] compressPullFullPayload(byte[] raw) {
        try {
            return CompressionService.getInstance().compress(raw,
                    Constants.NETWORK_COMPRESSION_ALGORITHM,
                    HassiumConfigService.getNetworkCompressionLevel());
        } catch (CompressionException e) {
            Constants.LOG.warn("Hassium: pull FULL payload compress failed", e);
            return null;
        }
    }

    /**
     * 每玩家 Pull 工作队列。收包只入队；hash/比较/编码在 tick 阀门后。
     * 未就绪柱仅在 lookahead 内持 FORCED 票（只装载，入队当时不发票）。
     */
    private static final class PendingPull {
        final ServerPlayer player;
        final ServerLevel level;
        final String dimension;
        final ShadowPullRequestC2SPacket.Entry entry;
        final long requestId;
        final long epoch;
        final long enqueueNanos;
        boolean ticketed;
        long ticketNanos;

        PendingPull(ServerPlayer player, ServerLevel level, String dimension,
                    ShadowPullRequestC2SPacket.Entry entry, long requestId, long epoch) {
            this.player = player;
            this.level = level;
            this.dimension = dimension;
            this.entry = entry;
            this.requestId = requestId;
            this.epoch = epoch;
            this.enqueueNanos = System.nanoTime();
        }

        ServerPlayer player() {
            return player;
        }

        ServerLevel level() {
            return level;
        }

        String dimension() {
            return dimension;
        }

        ShadowPullRequestC2SPacket.Entry entry() {
            return entry;
        }

        long requestId() {
            return requestId;
        }

        long epoch() {
            return epoch;
        }

        long enqueueNanos() {
            return enqueueNanos;
        }
    }

    private final Map<UUID, ArrayDeque<PendingPull>> pullQueues = new ConcurrentHashMap<>();
    /** 每玩家已入队柱（DimensionKey），同格重复 Pull 丢弃。 */
    private final Map<UUID, Set<Long>> pullQueuedKeys = new ConcurrentHashMap<>();
    /** 按需加载引用计数：pos → 持有 FORCED 票的未就绪 pull 数（归零移除原版票）。 */
    private final java.util.Map<Long, Integer> demandTicketRefs = new java.util.HashMap<>();
    private static final int MAX_PENDING_PULLS = 4096;
    private static final long PENDING_PULL_TIMEOUT_NANOS = 15_000_000_000L;

    private int totalPullQueued() {
        int n = 0;
        for (ArrayDeque<PendingPull> q : pullQueues.values()) {
            n += q.size();
        }
        return n;
    }

    private void enqueuePendingPull(ServerPlayer player, ServerLevel level,
                                    ShadowPullRequestC2SPacket request,
                                    ShadowPullRequestC2SPacket.Entry entry, String dimension) {
        if (request == null || level == null || player == null || entry == null) {
            return;
        }
        long columnKey = DimensionKey.key(dimension, entry.chunkX(), entry.chunkZ());
        Set<Long> keys = pullQueuedKeys.computeIfAbsent(player.getUUID(),
                ignored -> ConcurrentHashMap.newKeySet());
        if (!keys.add(columnKey)) {
            return;
        }
        if (totalPullQueued() >= MAX_PENDING_PULLS) {
            evictOldestPull();
        }
        pullQueues.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>())
                .add(new PendingPull(player, level, dimension, entry,
                        request.requestId(), request.epoch()));
    }

    private void unqueuePull(PendingPull pending) {
        if (pending == null || pending.player() == null) {
            return;
        }
        Set<Long> keys = pullQueuedKeys.get(pending.player().getUUID());
        if (keys != null) {
            keys.remove(DimensionKey.key(pending.dimension(),
                    pending.entry().chunkX(), pending.entry().chunkZ()));
        }
    }

    private void evictOldestPull() {
        PendingPull oldest = null;
        ArrayDeque<PendingPull> oldestQueue = null;
        for (ArrayDeque<PendingPull> q : pullQueues.values()) {
            PendingPull head = q.peek();
            if (head == null) {
                continue;
            }
            if (oldest == null || head.enqueueNanos() < oldest.enqueueNanos()) {
                oldest = head;
                oldestQueue = q;
            }
        }
        if (oldestQueue == null) {
            return;
        }
        PendingPull evicted = oldestQueue.poll();
        if (evicted != null) {
            releaseDemandTicket(evicted);
            unqueuePull(evicted);
            sendPullFailure(evicted, "overflow");
        }
    }

    private void acquireDemandTicket(PendingPull pending) {
        if (pending.ticketed) {
            return;
        }
        ChunkPos pos = new ChunkPos(pending.entry().chunkX(), pending.entry().chunkZ());
        long key = pos.toLong();
        int refs = demandTicketRefs.merge(key, 1, Integer::sum);
        pending.ticketed = true;
        pending.ticketNanos = System.nanoTime();
        if (refs != 1) {
            return;
        }
#if MC_VER < MC_1_21_5
        pending.level().getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, pos, 0, pos);
#else
        try {
            ((io.github.limuqy.mc.hassium.mixin.ServerChunkCacheAccessor) (Object) pending.level().getChunkSource())
                    .hassium$getTicketStorage()
                    .addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, 0);
        } catch (Throwable ignored) {
            // ticketStorage 不可达时按无票处理（world 启动期 / 异常关闭）
        }
#endif
    }

    private void releaseDemandTicket(PendingPull pending) {
        if (!pending.ticketed) {
            return;
        }
        pending.ticketed = false;
        ChunkPos pos = new ChunkPos(pending.entry().chunkX(), pending.entry().chunkZ());
        long key = pos.toLong();
        Integer refs = demandTicketRefs.get(key);
        if (refs == null) {
            return;
        }
        if (refs <= 1) {
            demandTicketRefs.remove(key);
            try {
#if MC_VER < MC_1_21_5
                pending.level().getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, pos, 0, pos);
#else
                ((io.github.limuqy.mc.hassium.mixin.ServerChunkCacheAccessor) (Object) pending.level().getChunkSource())
                        .hassium$getTicketStorage()
                        .removeTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, 0);
#endif
            } catch (Throwable ignored) {
                // world 已停：票随实例销毁
            }
        } else {
            demandTicketRefs.put(key, refs - 1);
        }
    }

    /** 每 tick 每玩家泵：lookahead 发票 + 阀门内 hash/比较 + FULL/DELTA 下推 pushPool。 */
    private void pumpPendingPulls() {
        if (pullQueues.isEmpty()) {
            return;
        }
        int configured = HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick();
        int fullBudget = PullPacingValve.fullDeltaBudget(configured);
        int hashBudget = PullPacingValve.hashBudget(configured);
        int lookahead = PullPacingValve.lookaheadLimit(configured);
        long now = System.nanoTime();
        for (Map.Entry<UUID, ArrayDeque<PendingPull>> e : pullQueues.entrySet()) {
            pumpPlayerPulls(e.getValue(), fullBudget, hashBudget, lookahead, now);
        }
        pullQueues.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private void pumpPlayerPulls(ArrayDeque<PendingPull> queue, int fullBudget, int hashBudget,
                                 int lookahead, long now) {
        if (queue.isEmpty()) {
            return;
        }
        List<PendingPull> live = new ArrayList<>(queue.size());
        while (!queue.isEmpty()) {
            PendingPull pending = queue.poll();
            if (dropOrKeepLive(pending, now, live)) {
                continue;
            }
        }
        if (live.isEmpty()) {
            return;
        }
        PendingPull sample = live.get(0);
        ChunkPos center = sample.player().chunkPosition();
        List<PullPacingValve.Candidate> candidates = new ArrayList<>(live.size());
        int ticketedUnready = 0;
        for (int i = 0; i < live.size(); i++) {
            PendingPull p = live.get(i);
            ServerLevel level = PlayerCompat.getServerLevel(p.player());
            boolean ready = level != null && LevelCompat.loadedFullChunk(
                    level, p.entry().chunkX(), p.entry().chunkZ()) != null;
            if (!ready && p.ticketed) {
                ticketedUnready++;
            }
            candidates.add(new PullPacingValve.Candidate(
                    i, p.entry().chunkX(), p.entry().chunkZ(), ready, p.ticketed));
        }
        for (int idx : PullPacingValve.selectLookaheadTickets(
                candidates, center.x, center.z, ticketedUnready, lookahead)) {
            acquireDemandTicket(live.get(idx));
        }
        List<Integer> selected = PullPacingValve.selectReadyToHash(
                candidates, center.x, center.z, hashBudget);
        boolean[] completed = new boolean[live.size()];
        int unchangedLeft = PullPacingValve.UNCHANGED_PER_TICK;
        int fullLeft = fullBudget;
        for (int idx : selected) {
            PendingPull pending = live.get(idx);
            boolean hasBaseline = PullPacingValve.hasBaseline(
                    pending.entry().chunkHash(), pending.entry().sectionHashes());
            if (PullPacingValve.skipHash(fullLeft, unchangedLeft, hasBaseline)) {
                continue;
            }
            if (!completeReadyPull(pending, unchangedLeft, fullLeft)) {
                continue;
            }
            completed[idx] = true;
            if (lastCompletedUnchanged) {
                unchangedLeft--;
            } else {
                fullLeft--;
            }
        }
        for (int i = 0; i < live.size(); i++) {
            if (!completed[i]) {
                queue.add(live.get(i));
            }
        }
    }

    /** {@link #completeReadyPull} 最近一次完成是否为 UNCHANGED（供配额记账）。 */
    private boolean lastCompletedUnchanged;

    /**
     * @return false = 本柱应留在队列（未就绪或本 tick 配额不足）
     */
    private boolean dropOrKeepLive(PendingPull pending, long now, List<PendingPull> live) {
        ServerPlayer player = pending.player();
        if (player == null || player.hasDisconnected() || player.isRemoved()) {
            releaseDemandTicket(pending);
            unqueuePull(pending);
            return true;
        }
        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null || !LevelCompat.getDimensionId(level).equals(pending.dimension())) {
            releaseDemandTicket(pending);
            unqueuePull(pending);
            return true;
        }
        LevelChunk chunk = LevelCompat.loadedFullChunk(level, pending.entry().chunkX(), pending.entry().chunkZ());
        if (chunk == null
                && pending.ticketed
                && now - pending.ticketNanos > PENDING_PULL_TIMEOUT_NANOS) {
            // 仅已发票仍未 FULL：装载失败。未持票的柱在 lookahead 外排队，不能按入队时钟超时。
            releaseDemandTicket(pending);
            unqueuePull(pending);
            sendPullFailure(pending, "unloaded");
            return true;
        }
        live.add(pending);
        return false;
    }

    /**
     * 阀门内：主线程 hash/比较；UNCHANGED 当场发送；FULL/DELTA 快照后 pushPool encode+zstd。
     *
     * @return true 已完成（从队列移除）；false 应归还队列
     */
    private boolean completeReadyPull(PendingPull pending, int unchangedLeft, int fullLeft) {
        lastCompletedUnchanged = false;
        ServerLevel level = PlayerCompat.getServerLevel(pending.player());
        if (level == null) {
            return false;
        }
        LevelChunk chunk = LevelCompat.loadedFullChunk(level, pending.entry().chunkX(), pending.entry().chunkZ());
        if (chunk == null) {
            return false;
        }
        try {
            ClassifiedPull classified = classifyPull(pending.entry(), chunk, level);
            if (classified.unchanged()) {
                if (unchangedLeft <= 0) {
                    return false;
                }
                lastCompletedUnchanged = true;
                releaseDemandTicket(pending);
                unqueuePull(pending);
                sendPullResponse(pending.player(), pending, classified.result());
                return true;
            }
            if (fullLeft <= 0) {
                return false;
            }
            lastCompletedUnchanged = false;
            releaseDemandTicket(pending);
            unqueuePull(pending);
            submitEncodedPull(pending, classified, level.registryAccess());
            return true;
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: pending pull push failed for ({}, {})",
                    pending.entry().chunkX(), pending.entry().chunkZ(), t);
            releaseDemandTicket(pending);
            unqueuePull(pending);
            sendPullFailure(pending, "push");
            return true;
        }
    }

    /**
     * 收包线程只入队，返回 null（本响应省略该柱）。越界/维度错误由 Handler 当场 ERROR。
     * hash / 比较 / 编码一律在 tick 阀门后。
     */
    public ShadowPullResponseS2CPacket.Result resolveShadowPull(ServerPlayer player,
                                                                  ShadowPullRequestC2SPacket request,
                                                                  ShadowPullRequestC2SPacket.Entry entry,
                                                                  String dimension) {
        if (player == null || entry == null) {
            return null;
        }
        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null || !LevelCompat.getDimensionId(level).equals(dimension)) {
            return ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "dimension");
        }
        try {
            enqueuePendingPull(player, level, request, entry, dimension);
            return null;
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: shadowPullV1 enqueue failed for ({}, {})",
                    entry.chunkX(), entry.chunkZ(), t);
            return ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "load");
        }
    }

    private record ClassifiedPull(boolean unchanged, ShadowPullResponseS2CPacket.Result result,
                                  ClientboundLevelChunkWithLightPacket fullPacket,
                                  SectionDeltaColumnSnap deltaSnap,
                                  long chunkHash, List<Long> sectionHashList) {
    }

    /** 主线程：hash + 比较 + FULL 包快照 / DELTA 列快照。encode/zstd 在 pushPool。 */
    private ClassifiedPull classifyPull(ShadowPullRequestC2SPacket.Entry entry,
                                        LevelChunk chunk, ServerLevel level) {
        Map<Integer, Long> hashes = ChunkContentHashUtil.computeSectionHashes(chunk);
        long chunkHash = ChunkContentHashUtil.combineSectionHashes(hashes);
        long[] sectionHashArray = ChunkContentHashUtil.sectionHashesToArray(hashes);
        List<Long> sectionHashList = new ArrayList<>(sectionHashArray.length);
        for (long hash : sectionHashArray) {
            sectionHashList.add(hash);
        }
        boolean hashMatch = entry.chunkHash() != 0L && entry.chunkHash() == chunkHash;
        boolean sectionsMatch = !entry.sectionHashes().isEmpty() && entry.sectionHashes().equals(sectionHashList);
        if (hashMatch || sectionsMatch) {
            return new ClassifiedPull(true, ShadowPullResponseS2CPacket.Result.unchanged(
                    entry.chunkX(), entry.chunkZ(), chunkHash, sectionHashList),
                    null, null, chunkHash, sectionHashList);
        }
        SectionDeltaColumnSnap deltaSnap = null;
        if (!entry.sectionHashes().isEmpty()
                && HassiumConfigService.getInstance().isSectionDeltaEnabled()) {
            deltaSnap = snapshotSectionDeltaColumn(chunk);
        }
        ClientboundLevelChunkWithLightPacket packet = buildChunkPacket(chunk, level);
        return new ClassifiedPull(false, null, packet, deltaSnap, chunkHash, sectionHashList);
    }

    private void submitEncodedPull(PendingPull pending, ClassifiedPull classified, RegistryAccess registryAccess) {
        ensureInitialized();
        ShadowPullRequestC2SPacket.Entry entry = pending.entry();
        pushPool.submit(() -> {
            try {
                ShadowPullResponseS2CPacket.Result result = encodeClassifiedPull(pending, classified, entry, registryAccess);
                if (pending.player() == null || pending.player().hasDisconnected()) {
                    return;
                }
                sendPullResponse(pending.player(), pending, result);
            } catch (Throwable t) {
                Constants.LOG.warn("Hassium: pull encode/send failed for ({}, {})",
                        entry.chunkX(), entry.chunkZ(), t);
                sendPullFailure(pending, "encode");
            }
        });
    }

    private ShadowPullResponseS2CPacket.Result encodeClassifiedPull(PendingPull pending, ClassifiedPull classified,
                                                                    ShadowPullRequestC2SPacket.Entry entry,
                                                                    RegistryAccess registryAccess) {
        if (classified.deltaSnap() != null) {
            SectionDeltaS2CPacket.DeltaEntry planned = planAndSerialize(classified.deltaSnap(), entry);
            if (planned != null) {
                SectionDeltaS2CPacket delta = new SectionDeltaS2CPacket(
                        pending.dimension(), List.of(planned), List.of());
                FriendlyByteBuf deltaBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    delta.encode(deltaBuf);
                    byte[] payload = new byte[deltaBuf.readableBytes()];
                    deltaBuf.readBytes(payload);
                    return ShadowPullResponseS2CPacket.Result.payload(entry.chunkX(), entry.chunkZ(),
                            ShadowPullResponseS2CPacket.Kind.DELTA, classified.chunkHash(),
                            classified.sectionHashList(), payload);
                } finally {
                    deltaBuf.release();
                }
            }
        }
        byte[] payload = classified.fullPacket() == null
                ? null
                : encodeChunkPacket(classified.fullPacket(), registryAccess);
        if (payload != null) {
            payload = compressPullFullPayload(payload);
        }
        return payload == null
                ? ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "encode")
                : ShadowPullResponseS2CPacket.Result.payload(entry.chunkX(), entry.chunkZ(),
                ShadowPullResponseS2CPacket.Kind.FULL, classified.chunkHash(),
                classified.sectionHashList(), payload);
    }



    /** 待推送柱主动推送（复用原 requestId：客户端按原分类记账）。 */
    private void sendPullResponse(ServerPlayer player, PendingPull pending,
                                  ShadowPullResponseS2CPacket.Result result) {
        ShadowPullResponseS2CPacket response = new ShadowPullResponseS2CPacket(
                pending.dimension(), pending.epoch(), pending.requestId(), List.of(result));
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        boolean handedOff = false;
        try {
            response.encode(buf);
            io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.sendShadowPullResponse(player, buf);
            // 所有权已转移：fabric send 直接持有 buf（不在此释放），
            // forge/neoforge 实现内部已拷贝并释放。
            handedOff = true;
        } finally {
            if (!handedOff && buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    /** 待推送柱终态失败（超时/卸载/溢出）：客户端不重试。 */
    private void sendPullFailure(PendingPull pending, String reason) {
        if (pending.player() == null || pending.player().hasDisconnected()) {
            return;
        }
        sendPullResponse(pending.player(), pending, ShadowPullResponseS2CPacket.Result.error(
                pending.entry().chunkX(), pending.entry().chunkZ(), reason));
    }


    /**
     * 握手 C2S 能力上报后调用：记录玩家是否支持光照计算（影子端/Hassium 引擎）。
     */
    public void setPlayerLightComputeSupported(UUID playerId, boolean supported) {
        if (supported) {
            playerLightComputeSupported.put(playerId, Boolean.TRUE);
        } else {
            playerLightComputeSupported.remove(playerId);
        }
    }

    /** 该玩家是否可剥光（客户端声明引擎可用）。 */
    public boolean isPlayerLightComputeSupported(UUID playerId) {
        return Boolean.TRUE.equals(playerLightComputeSupported.get(playerId));
    }

    /**
     * 握手上报的玩家初始 chunk 位置（playerId → ChunkPos）。
     * 服务端玩家对象在 failover/重连场景位置滞后（新对象在出生点），resync 视距中心
     * 先用客户端上报的真实位置校正；消费（resync 中心计算）后移除，后续用玩家对象实时位置。
     */
    private final Map<UUID, ChunkPos> initialPlayerChunkPos = new ConcurrentHashMap<>();

    /** 玩家就绪时记录初始位置（直连拓扑：服务端玩家对象自带坐标；removePlayer 清理）。 */
    public void setInitialPlayerPosition(ServerPlayer player, double x, double z) {
        if (player == null) {
            return;
        }
        ChunkPos pos = new ChunkPos((int) Math.floor(x / 16.0), (int) Math.floor(z / 16.0));
        initialPlayerChunkPos.put(player.getUUID(), pos);
        DebugLogger.info(LogType.NETWORK,
                "[PLAY_INIT] Player {} initial position ({}, {}) → chunk ({}, {})",
                player.getName().getString(), x, z, pos.x, pos.z);
    }

    /**
     * 数据请求处理线程池（hash 计算 + 压缩发送）
     */
    private volatile ThreadPoolExecutor pushPool;

    /**
     * 线程池是否已初始化
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private ServerChunkPushManager() {}

    public static ServerChunkPushManager getInstance() {
        return INSTANCE;
    }

    /**
     * 服务端推送管线计时诊断（R1 供给版本差异排查：1.20.1 80/s vs 1.21.x 32/s）。
     * 每 256 块打印一次各段均值（build=主线程重建 packet / hash=pushPool 哈希 /
     * encode=线格式编码 / send=压缩+发送），打印后清零。热路径仅加 Atomic 累加。
     */
    private static final int D_BUILD = 0, D_HASH = 1, D_ENCODE = 2, D_SEND = 3;
    private static final AtomicLongArray DIAG_NS = new AtomicLongArray(4);
    private static final AtomicLong DIAG_COUNT = new AtomicLong();

    private static void diag(int slot, long ns) {
        DIAG_NS.addAndGet(slot, ns);
        long c = DIAG_COUNT.incrementAndGet();
        if ((c & 0xFF) == 0L) {
            Constants.LOG.info(
                    "[SERVE-DIAG] chunks={} build={}ms hash={}ms encode={}ms send={}ms",
                    c,
                    String.format("%.2f", DIAG_NS.get(D_BUILD) / 1e6 / 256.0),
                    String.format("%.2f", DIAG_NS.get(D_HASH) / 1e6 / 256.0),
                    String.format("%.2f", DIAG_NS.get(D_ENCODE) / 1e6 / 256.0),
                    String.format("%.2f", DIAG_NS.get(D_SEND) / 1e6 / 256.0));
            for (int i = 0; i < 4; i++) {
                DIAG_NS.set(i, 0L);
            }
        }
    }

    /**
     * 初始化线程池（懒加载）
     */
    private void ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            // 全局计算池：核数硬编码（encode/hash/ZSTD），与配置解耦
            int threads = Runtime.getRuntime().availableProcessors();
            pushPool = new ThreadPoolExecutor(
                    threads,
                    threads,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "Hassium-ChunkPush");
                        t.setDaemon(true);
                        return t;
                    }
            );
            pushPool.allowCoreThreadTimeOut(true);

            Constants.LOG.info("Hassium: ServerChunkPushManager initialized with {} compute threads",
                    threads);
        }
    }


    /** 服务端每 tick：泵待推送 Pull 队列。 */
    public void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        ensureInitialized();
        pumpPendingPulls();
    }



    /**
     * 对已脱离 live world 的 section 拷贝做 Planner → FULL / BLOCKS。
     * 返回 null 表示整块 skipped（75% 回退）。
     */
    private SectionDeltaS2CPacket.DeltaEntry planAndSerialize(SectionDeltaColumnSnap snap,
                                                             ShadowPullRequestC2SPacket.Entry clientEntry) {
        SectionDeltaSnapshot serverSnap = SectionDeltaSnapshot.capture(snap.sections());
        long[] clientSectionHashes = new long[clientEntry.sectionHashes().size()];
        for (int index = 0; index < clientSectionHashes.length; index++) {
            clientSectionHashes[index] = clientEntry.sectionHashes().get(index);
        }
        SectionDeltaSnapshot clientSnap = new SectionDeltaSnapshot(clientSectionHashes, clientEntry.planes());
        SectionDeltaPlanner.ChunkDecision decision = SectionDeltaPlanner.plan(clientSnap, serverSnap);
        if (decision.skipWholeChunk()) {
            DebugLogger.info(LogType.NETWORK,
                    "[SECTION_DELTA] Fallback to full for [{}, {}]: FULL sections >= {}%",
                    clientEntry.chunkX(), clientEntry.chunkZ(),
                    SectionDeltaPlanner.FALLBACK_THRESHOLD_PCT);
            return null;
        }
        List<SectionDeltaS2CPacket.SectionData> changedSections = new ArrayList<>();
        for (SectionDeltaPlanner.SectionDecision sd : decision.sections()) {
            if (sd.kind() == SectionDeltaPlanner.Kind.SKIP) {
                continue;
            }
            LevelChunkSection section = sectionAt(snap.sections(), sd.sectionIndex());
            if (section == null) {
                continue;
            }
            if (sd.kind() == SectionDeltaPlanner.Kind.FULL) {
                changedSections.add(new SectionDeltaS2CPacket.SectionData(
                        sd.sectionIndex(), SectionDeltaS2CPacket.KIND_FULL,
                        writeSectionBytes(section)));
                continue;
            }
            byte[] full = writeSectionBytes(section);
            int[] stateIds = stateIdsOf(section, sd.candidates());
            byte[] blocks = SectionPlaneSyndrome.encodeBlockList(sd.candidates(), stateIds);
            if (blocks.length <= full.length) {
                changedSections.add(new SectionDeltaS2CPacket.SectionData(
                        sd.sectionIndex(), SectionDeltaS2CPacket.KIND_BLOCKS, blocks));
            } else {
                changedSections.add(new SectionDeltaS2CPacket.SectionData(
                        sd.sectionIndex(), SectionDeltaS2CPacket.KIND_FULL, full));
            }
        }
        int nonEmptySections = 0;
        int fullSections = 0;
        for (int idx = 0; idx < serverSnap.sectionCount(); idx++) {
            if (serverSnap.sectionHash(idx) != 0L) {
                nonEmptySections++;
            }
        }
        for (SectionDeltaS2CPacket.SectionData sectionData : changedSections) {
            if (sectionData.kind() == SectionDeltaS2CPacket.KIND_FULL
                    && serverSnap.sectionHash(sectionData.sectionIndex()) != 0L) {
                fullSections++;
            }
        }
        if (nonEmptySections > 0 && fullSections > 0
                && fullSections * 100 / nonEmptySections >= SectionDeltaPlanner.FALLBACK_THRESHOLD_PCT) {
            DebugLogger.info(LogType.NETWORK,
                    "[SECTION_DELTA] Fallback to full for [{}, {}]: encoded FULL sections >= {}% ({}/{})",
                    clientEntry.chunkX(), clientEntry.chunkZ(),
                    SectionDeltaPlanner.FALLBACK_THRESHOLD_PCT, fullSections, nonEmptySections);
            return null;
        }
        long expectedChunkHash = ChunkContentHashUtil.combineSectionHashesFromArray(serverSnap.sectionHashes());
        return new SectionDeltaS2CPacket.DeltaEntry(
                clientEntry.chunkX(), clientEntry.chunkZ(), changedSections,
                snap.heightmaps(), snap.blockEntities(), expectedChunkHash);
    }

    private static LevelChunkSection sectionAt(LevelChunkSection[] sections, int index) {
        return index >= 0 && index < sections.length ? sections[index] : null;
    }

    /** 主线程：PalettedContainer 拷贝 + heightmap/BE 快照，不再 hash / 扫格 / write。 */
    private SectionDeltaColumnSnap snapshotSectionDeltaColumn(LevelChunk chunk) {
        LevelChunkSection[] src = chunk.getSections();
        LevelChunkSection[] copies = new LevelChunkSection[src.length];
        for (int i = 0; i < src.length; i++) {
            if (src[i] == null) {
                continue;
            }
            copies[i] = LevelChunkSectionCompat.copyDetached(src[i]);
        }
        return new SectionDeltaColumnSnap(copies, collectHeightmaps(chunk), collectBlockEntities(chunk));
    }

    private int[] stateIdsOf(LevelChunkSection section, int[] candidates) {
        int[] ids = new int[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            int packed = candidates[i];
            ids[i] = LevelChunkSectionCompat.blockStateId(section.getBlockState(
                    SectionPlaneSyndrome.localX(packed),
                    SectionPlaneSyndrome.localY(packed),
                    SectionPlaneSyndrome.localZ(packed)));
        }
        return ids;
    }

    private byte[] writeSectionBytes(LevelChunkSection section) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            section.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(0, data);
            return data;
        } finally {
            buf.release();
        }
    }

    /**
     * 收集 chunk 全部 heightmap rawData（FULL status 的 types；与服务端 chunk 当前状态一致）。
     * delta 包不含 heightmap 线格式，必须随包下发，客户端 merge 后逐 type setHeightmap。
     */
    private List<SectionDeltaS2CPacket.HeightmapData> collectHeightmaps(LevelChunk chunk) {
        List<SectionDeltaS2CPacket.HeightmapData> result = new ArrayList<>();
        for (var entry : chunk.getHeightmaps()) {
            long[] raw = entry.getValue().getRawData();
            result.add(new SectionDeltaS2CPacket.HeightmapData(
                    entry.getKey().ordinal(), raw != null ? raw.clone() : new long[0]));
        }
        return result;
    }

    /**
     * 收集 chunk 中所有 blockEntity 的数据
     */
    @SuppressWarnings("deprecation") // Forge: BuiltInRegistries 字段在 Forge patched jar 中被标记 @Deprecated
    private List<SectionDeltaS2CPacket.BlockEntityData> collectBlockEntities(LevelChunk chunk) {
        List<SectionDeltaS2CPacket.BlockEntityData> result = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockEntity be = entry.getValue();
            // 自定义 chunk/delta 通道与 vanilla 初始区块包语义一致：只发送客户端
            // update NBT。saveWithoutMetadata 会携带 TrialSpawnerLogic 的服务器 worldgen
            // registry 引用；1.21.5+ 客户端并未同步 trial_spawner registry，load 即报错。
#if MC_VER < MC_1_21_1
            CompoundTag nbt = be.getUpdateTag();
#else
            CompoundTag nbt = be.getUpdateTag(be.getLevel().registryAccess());
#endif
            String type = String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
            result.add(new SectionDeltaS2CPacket.BlockEntityData(pos, type, nbt));
        }
        return result;
    }


    /**
     * 仅替换既有 packet 的 light payload；chunk data 保持原版/兼容 Mod 已写入的不可变视图。
     * 广播包会被所有 Hassium 玩家共享，混合 light capability 时不能原地修改，整体回退原 light。
     */
    private ClientboundLevelChunkWithLightPacket stripLightIfConfigured(
            List<ServerPlayer> players, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (!HassiumConfigService.getInstance().isServerLightStrip() || players == null || players.isEmpty()) {
            return packet;
        }
        for (ServerPlayer player : players) {
            if (!isPlayerLightComputeSupported(player.getUUID())) {
                return packet;
            }
        }
        return stripLightInPlace(pos, packet);
    }

    private ClientboundLevelChunkWithLightPacket stripLightIfConfigured(
            ServerPlayer player, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (!HassiumConfigService.getInstance().isServerLightStrip()
                || !isPlayerLightComputeSupported(player.getUUID())) {
            return packet;
        }
        return stripLightInPlace(pos, packet);
    }

    private ClientboundLevelChunkWithLightPacket stripLightInPlace(
            ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            ChunkPacketDataCompat.writeEmptyLightData(buffer);
            ClientboundLightUpdatePacketData emptyLight =
                    new ClientboundLightUpdatePacketData(buffer, pos.x, pos.z);
            ((LevelChunkWithLightPacketAccessor) (Object) packet).hassium$setLightData(emptyLight);
            return packet;
        } catch (Exception e) {
            Constants.LOG.warn("[LIGHT-STRIP] Failed to replace light payload at {}; retaining vanilla light", pos, e);
            return packet;
        } finally {
            buffer.release();
        }
    }

    /**
     * 按原版构造路径构建区块包。必须在拥有 LevelChunk 的调用线程执行；packet 构造完成后
     * 已持有 section/light 的序列化快照，可安全地在 pushPool 编码、压缩并发送。
     */
    private ClientboundLevelChunkWithLightPacket buildChunkPacket(LevelChunk chunk, ServerLevel level) {
        try {
            boolean stripLight = HassiumConfigService.getInstance().isServerLightStrip();
            java.util.BitSet lightMask = stripLight ? new java.util.BitSet() : null;
            return new ClientboundLevelChunkWithLightPacket(
                    chunk, level.getLightEngine(), lightMask, lightMask);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to build chunk packet {}", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * 将区块包编码为线格式字节（RegistryAccess 服务端启动后只读，任意线程编码安全）。
     */
    @SuppressWarnings("deprecation") // NeoForge 1.21.11+: RegistryFriendlyByteBuf(2-param) deprecated; 3-param 需 ConnectionType.OTHER(仅 NeoForge)
    private byte[] encodeChunkPacket(ClientboundLevelChunkWithLightPacket chunkPacket,
                                     RegistryAccess registryAccess) {
#if MC_VER < MC_1_21_1
        io.netty.buffer.ByteBuf tempBuf = io.netty.buffer.Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(tempBuf);
            chunkPacket.write(friendlyBuf);
            byte[] data = new byte[tempBuf.readableBytes()];
            tempBuf.getBytes(0, data);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to encode chunk packet", e);
            return null;
        } finally {
            tempBuf.release();
        }
#else
        net.minecraft.network.RegistryFriendlyByteBuf buf =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), registryAccess);
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, chunkPacket);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to encode chunk packet", e);
            return null;
        } finally {
            buf.release();
        }
#endif
    }


    /**
     * 移除玩家的 Pull 队列与能力表。
     */
    public void removePlayer(UUID playerId) {
        ArrayDeque<PendingPull> pulls = pullQueues.remove(playerId);
        if (pulls != null) {
            for (PendingPull pending : pulls) {
                releaseDemandTicket(pending);
            }
        }
        pullQueuedKeys.remove(playerId);
        initialPlayerChunkPos.remove(playerId);
        playerLightComputeSupported.remove(playerId);
    }

    /**
     * 清空所有队列并关闭线程池
     */
    public void shutdown() {
        for (ArrayDeque<PendingPull> pulls : pullQueues.values()) {
            for (PendingPull pending : pulls) {
                releaseDemandTicket(pending);
            }
        }
        pullQueues.clear();
        pullQueuedKeys.clear();
        demandTicketRefs.clear();
        initialPlayerChunkPos.clear();
        playerLightComputeSupported.clear();
        if (pushPool != null) {
            pushPool.shutdownNow();
        }
        initialized.set(false);
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        int totalPending = pullQueues.values().stream().mapToInt(ArrayDeque::size).sum();
        int poolSize = pushPool != null ? pushPool.getPoolSize() : 0;
        int activeThreads = pushPool != null ? pushPool.getActiveCount() : 0;
        return String.format("PendingPulls: %d, Threads: %d/%d",
                totalPending, activeThreads, poolSize);
    }

    /** 已脱离 live world 的柱数据；后台可自由读。 */
    private record SectionDeltaColumnSnap(
            LevelChunkSection[] sections,
            List<SectionDeltaS2CPacket.HeightmapData> heightmaps,
            List<SectionDeltaS2CPacket.BlockEntityData> blockEntities) {}
}
