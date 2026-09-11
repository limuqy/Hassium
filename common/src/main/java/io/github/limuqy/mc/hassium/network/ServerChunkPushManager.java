package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;
import io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.compression.CompressionException;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.utils.TickMonitor;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaPlanner;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshot;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import io.github.limuqy.mc.hassium.mixin.LevelChunkWithLightPacketAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 服务端区块推送管理器
 * <p>
 * 职责：
 * 1. 发送 chunkHash 元数据给客户端
 * 2. 管理区块数据请求队列：主线程序列化，线程池异步压缩发送
 * 3. 短窗口批量发送 ChunkHash，降低进服包风暴
 * 4. 缓存拦截时已构建的区块包字节，miss 全量时复用（兼容反透视等改包 mod）
 */
public class ServerChunkPushManager {

    private static final ServerChunkPushManager INSTANCE = new ServerChunkPushManager();

    /** 每玩家已封装且仍在通道中排队的批次上限；满则跳过本 tick 封批。 */
    static final int MAX_QUEUED_BATCHES_PER_PLAYER = 10;
    /** 已准入任务满时的全量数据溢出上限；覆盖单玩家最大可见区，避免静默丢柱。 */
    private static final int MAX_OVERFLOW_TASKS_PER_PLAYER = 8192;
    private static final long PENDING_CONFIRM_TIMEOUT_MS = 60_000L;
    private static final int SECTION_DELTA_VIEW_MARGIN = 1;

    /**
     * UNCHANGED 每 tick 配额（不占 FULL/DELTA）。与 {@link PullPacingValve#UNCHANGED_PER_TICK} 同值。
     */
    static final int HASH_SENDS_PER_TICK = PullPacingValve.UNCHANGED_PER_TICK;

    /**
     * 每玩家推送队列：per-player FIFO 批次队列 + 每 tick 封批（≤maxChunksPerTick）。
     * 主线程 buildChunkPacket 快照在封批前完成，encode/hash/ZSTD 在消费线程。
     */
    private final Map<UUID, PlayerPushQueue> pushQueues = new ConcurrentHashMap<>();


    /**
     * 批次通道：主线程封批后投递，serverChunkPushThreads 条常驻消费者共享抢批。
     * 每个 {@link SealedBatch} 已在所属玩家队列中占用一个排队批次名额。
     */
    private final java.util.concurrent.LinkedBlockingQueue<SealedBatch> batchChannel =
            new java.util.concurrent.LinkedBlockingQueue<>();

    /**
     * 每玩家待发送的 chunkHash 批次


    /**
     * 每玩家光照计算能力（握手 C2S 上报 lightComputeSupported = 客户端 hassiumEngineEnabled）。
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
        if (request == null || level == null || player == null) {
            return;
        }
        if (totalPullQueued() >= MAX_PENDING_PULLS) {
            evictOldestPull();
        }
        pullQueues.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>())
                .add(new PendingPull(player, level, dimension, entry,
                        request.requestId(), request.epoch()));
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
        Set<Integer> hashIdx = new java.util.HashSet<>(
                PullPacingValve.selectReadyToHash(candidates, center.x, center.z, hashBudget));
        int unchangedLeft = PullPacingValve.UNCHANGED_PER_TICK;
        int fullLeft = fullBudget;
        for (int i = 0; i < live.size(); i++) {
            PendingPull pending = live.get(i);
            if (!hashIdx.contains(i)) {
                queue.add(pending);
                continue;
            }
            if (unchangedLeft <= 0 && fullLeft <= 0) {
                queue.add(pending);
                continue;
            }
            if (!completeReadyPull(pending, unchangedLeft, fullLeft)) {
                queue.add(pending);
                continue;
            }
            boolean spentUnchanged = lastCompletedUnchanged;
            if (spentUnchanged) {
                unchangedLeft--;
            } else {
                fullLeft--;
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
            return true;
        }
        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null || !LevelCompat.getDimensionId(level).equals(pending.dimension())) {
            releaseDemandTicket(pending);
            return true;
        }
        LevelChunk chunk = LevelCompat.loadedFullChunk(level, pending.entry().chunkX(), pending.entry().chunkZ());
        if (chunk == null
                && pending.ticketed
                && now - pending.ticketNanos > PENDING_PULL_TIMEOUT_NANOS) {
            // 仅已发票仍未 FULL：装载失败。未持票的柱在 lookahead 外排队，不能按入队时钟超时。
            releaseDemandTicket(pending);
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
                sendPullResponse(pending.player(), pending, classified.result());
                return true;
            }
            if (fullLeft <= 0) {
                return false;
            }
            lastCompletedUnchanged = false;
            releaseDemandTicket(pending);
            submitEncodedPull(pending, classified, level.registryAccess());
            return true;
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: pending pull push failed for ({}, {})",
                    pending.entry().chunkX(), pending.entry().chunkZ(), t);
            releaseDemandTicket(pending);
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

    /**
     * 握手时客户端上报的玩家位置（方块坐标），校正 resync 视距中心。
     * 服务端玩家位置同步前（首个移动包到达前），客户端坐标是最新鲜的来源。
     */
    /**
     * 续流已接受玩家：UUID → 接受的续流票据 epoch（T7 验票通过后标记；removePlayer 清理）。
     * 续流模式下客户端跳过 login/维度初始化；新 shadowPull 请求携带位置与 hash，
     * 服务端按请求结果返回区块终态。
     */
    private final Map<UUID, Long> resumePlayers = new ConcurrentHashMap<>();

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

    /** 续流/会话状态登记（直连拓扑保留空实现位，无票据来源）。 */
    public void markPlayerResumeActive(UUID playerId, long epoch) {
        resumePlayers.put(playerId, epoch);
        DebugLogger.info(LogType.NETWORK, "[RESUME] Player {} resume ready (epoch={})", playerId, epoch);
    }

    public boolean isPlayerResumeActive(UUID playerId) {
        return resumePlayers.containsKey(playerId);
    }

    public long playerResumeEpoch(UUID playerId) {
        return resumePlayers.getOrDefault(playerId, Long.MIN_VALUE);
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

    /** ChunkHash 单包最多 entries */
    private static final int HASH_BATCH_MAX_ENTRIES = 16;

    /** ChunkHash 批次最大等待（毫秒） */
    private static final long HASH_BATCH_MAX_WAIT_MS = 10;

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

            // 常驻消费者线程：serverChunkPushThreads 条共享抢批（LinkedBlockingQueue 批次通道）
            int consumers = HassiumConfigService.getInstance().getServerChunkPushThreads();
            for (int i = 0; i < consumers; i++) {
                Thread t = new Thread(this::consumeBatchesLoop, "Hassium-PushConsumer-" + i);
                t.setDaemon(true);
                t.start();
            }

            Constants.LOG.info("Hassium: ServerChunkPushManager initialized with {} compute threads, {} consumer threads",
                    threads, consumers);
        }
    }


    /** 服务端每 tick：按原版 tracking 产生的推送队列限流序列化。 */
    public void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        ensureInitialized();
        pumpPendingPulls();

        long now = System.currentTimeMillis();
        long drainPendingNs = 0L;
        long drainQueueNs = 0L;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerPushQueue playerQueue = pushQueues.get(player.getUUID());
            if (playerQueue != null) {
                playerQueue.promoteOverflow();
            }
            long t0 = System.nanoTime();
            t0 = System.nanoTime();
            sealPlayerBatch(player);
            drainQueueNs += System.nanoTime() - t0;
        }
        TickMonitor.addHassiumDrainNs(drainPendingNs, drainQueueNs);




        // 清理已离线玩家的批次
        pushQueues.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }



    /**
     * 统一入队入口：所有推送义务（fullReq、bloom miss 直推、resync 补发、出界复活、
     * section delta 响应）都经此进入 per-player FIFO 批次队列。排队批满则拒绝。
     *
     * @return true 入队成功或同柱已有任务排队
     */
    boolean enqueuePushTask(ServerPlayer player, ChunkPos pos, String dimension, PushKind kind) {
        if (player == null || pos == null || dimension == null) {
            return false;
        }
        if (!player.isAlive() || player.hasDisconnected()) {
            return false;
        }
        // 双保险：pull 模式玩家整柱数据类推送停发；mixin 层已先行拦截
        if (kind == PushKind.FULL_VISIBLE && isPullMode(player)) {
            return false;
        }
        PlayerPushQueue queue = pushQueues.computeIfAbsent(player.getUUID(), ignored -> new PlayerPushQueue());
        return queue.enqueue(new PushTask(pos, dimension, kind));
    }

    /** 该玩家是否协商了 Pull 模式（服务端停发 chunk_payload 整柱推送）。 */
    public boolean isPullMode(ServerPlayer player) {
        return player != null && io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation
                .hasCaps(player.getUUID(),
                        io.github.limuqy.mc.hassium.network.handshake.LoginCaps.PULL_MODE);
    }



    /**
     * 处理客户端的 blockEntity 数据请求。
     * <p>
     * 主线程只对已加载柱做 NBT 快照（{@code getChunkNow}）；组包发送下推 {@code pushPool}。
     */
    @SuppressWarnings("deprecation") // Forge: BuiltInRegistries 字段在 Forge patched jar 中被标记 @Deprecated
    public void handleBlockEntityRequest(ServerPlayer player, BlockEntityRequestC2SPacket request) {
        if (!player.isAlive() || player.hasDisconnected()) { return; }
        ensureInitialized();

        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null) {
            return;
        }
        int viewDistance = PlayerCompat.getViewDistance(player);
        ChunkPos playerChunkPos = player.chunkPosition();
        List<BlockEntityDataS2CPacket.ChunkBlockEntities> entries = new ArrayList<>();

        for (ChunkPos pos : request.chunks()) {
            try {
                int dx = Math.abs(pos.x - playerChunkPos.x);
                int dz = Math.abs(pos.z - playerChunkPos.z);
                if (dx > viewDistance || dz > viewDistance) { continue; }

                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) { continue; }

                entries.add(new BlockEntityDataS2CPacket.ChunkBlockEntities(
                        pos.x, pos.z, collectRequestedBlockEntities(chunk)));
            } catch (Exception e) {
                Constants.LOG.error("[BLOCK_ENTITY] Failed to collect block entities for chunk {}", pos, e);
            }
        }

        if (entries.isEmpty()) {
            return;
        }
        String dimension = request.dimension();
        pushPool.submit(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            FriendlyByteBuf buf = null;
            boolean sent = false;
            try {
                BlockEntityDataS2CPacket packet = new BlockEntityDataS2CPacket(dimension, entries);
                buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                packet.encode(buf);
                Services.NETWORK_MANAGER.sendBlockEntityData(player, buf);
                sent = true;
            } catch (Exception e) {
                Constants.LOG.error("[BLOCK_ENTITY] Failed to send block entity data", e);
            } finally {
                if (!sent && buf != null) {
                    buf.release();
                }
            }
        });
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

    private List<BlockEntityDataS2CPacket.BlockEntityData> collectRequestedBlockEntities(LevelChunk chunk) {
        List<SectionDeltaS2CPacket.BlockEntityData> src = collectBlockEntities(chunk);
        List<BlockEntityDataS2CPacket.BlockEntityData> out = new ArrayList<>(src.size());
        for (SectionDeltaS2CPacket.BlockEntityData be : src) {
            out.add(new BlockEntityDataS2CPacket.BlockEntityData(be.pos(), be.type(), be.nbt()));
        }
        return out;
    }


    /**
     * 旧服务端直推入队入口已退役：纯 Compare+Pull 下区块数据由客户端影子 tracking
     * 主动拉取；本地生成由门控开时的 vanilla worldgen 承担（无 SeedRef）。
     * 保留空实现供过渡期 mixin 调用，不入队。
     */
    public boolean enqueueDirectPush(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        return true;
    }

    /**
     * 主线程封批：队列在纯 Compare+Pull 下已无 FULL_VISIBLE/SeedRef 任务源；
     * 保留泵结构，有残留任务时直接掏空丢弃。
     */
    private void sealPlayerBatch(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerPushQueue queue = pushQueues.get(playerId);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        queue.clear();
    }

    /**
     * 常驻消费者循环：与其它消费者共享抢批（batchChannel 阻塞队列）。
     * 批内逐任务在 pushPool 上 encode/hash/ZSTD 后发送。
     */
    private void consumeBatchesLoop() {
        while (true) {
            SealedBatch sealed;
            try {
                sealed = batchChannel.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            sealed.owner().dequeueSealedBatch();
            try {
                processBatch(sealed.works());
            } catch (Throwable t) {
                Constants.LOG.error("Hassium: push consumer failed to process batch", t);
            }
        }
    }

    /** 消费一批：批>1 时 fan-out 全局池 invokeAll 同步等齐。 */
    private void processBatch(List<SealedWork> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (batch.size() == 1) {
            processOne(batch.get(0));
            return;
        }
        try {
            List<java.util.concurrent.Callable<Void>> callables = new ArrayList<>(batch.size());
            for (SealedWork work : batch) {
                callables.add(() -> {
                    processOne(work);
                    return null;
                });
            }
            pushPool.invokeAll(callables);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (SealedWork work : batch) {
                processOne(work);
            }
        }
    }

    /** 单任务消费：SeedRef 路径已退役，无事可做。 */
    private void processOne(SealedWork work) {
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
     * 移除玩家的所有队列（含 bloom 层——玩家断开后旧 bloom 必须失效：
     * 否则 R2 重连 trackChunk 会用 R1 残留的空 bloom 误判 miss → 全量直推，
     * bloom 分流退化为无缓存形态。清空后 R2 上报前走"未就绪只发 hash"，
     * 由影子端读盘比对决定本地回传/请求，语义正确）。
     */
    public void removePlayer(UUID playerId) {
        PlayerPushQueue queue = pushQueues.remove(playerId);
        if (queue != null) {
            queue.clear();
        }
        ArrayDeque<PendingPull> pulls = pullQueues.remove(playerId);
        if (pulls != null) {
            for (PendingPull pending : pulls) {
                releaseDemandTicket(pending);
            }
        }
        initialPlayerChunkPos.remove(playerId);
        resumePlayers.remove(playerId);
        playerLightComputeSupported.remove(playerId);
    }

    /**
     * 清空所有队列并关闭线程池
     */
    public void shutdown() {
        pushQueues.clear();
        for (ArrayDeque<PendingPull> pulls : pullQueues.values()) {
            for (PendingPull pending : pulls) {
                releaseDemandTicket(pending);
            }
        }
        pullQueues.clear();
        demandTicketRefs.clear();
        initialPlayerChunkPos.clear();
        resumePlayers.clear();
        // review-fix: T3-52：能力表一并清理
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
        int totalQueues = pushQueues.size();
        int totalPending = pushQueues.values().stream()
                .mapToInt(PlayerPushQueue::pendingCount)
                .sum();
        int poolSize = pushPool != null ? pushPool.getPoolSize() : 0;
        int activeThreads = pushPool != null ? pushPool.getActiveCount() : 0;
        return String.format("Queues: %d, Pending: %d, Threads: %d/%d",
                totalQueues, totalPending, activeThreads, poolSize);
    }

    /** 批次队列任务（SeedRef 已退役，仅保留位置/维度字段）。 */
    static record PushTask(ChunkPos pos, String dimension, PushKind kind) {
        static PushTask full(ChunkPos pos, String dimension, PushKind kind) {
            return new PushTask(pos, dimension, kind);
        }

        public ChunkPos pos() {
            return pos;
        }

        public String dimension() {
            return dimension;
        }
    }
    /** 推送任务类型（SeedRef 已退役；FULL_VISIBLE 仅作历史占位）。 */
    enum PushKind { FULL_VISIBLE }


    /**
     * 封批产物：主线程已完成世界快照（chunkData 或 packet），消费线程只做 encode/hash/ZSTD。
     */
    private record SealedWork(ServerPlayer player, PushTask task) {}

    /** 通道项及其所属玩家的已封装批次计数。 */
    private record SealedBatch(PlayerPushQueue owner, List<SealedWork> works) {}

    /** 已脱离 live world 的柱数据；后台可自由读。 */
    private record SectionDeltaColumnSnap(
            LevelChunkSection[] sections,
            List<SectionDeltaS2CPacket.HeightmapData> heightmaps,
            List<SectionDeltaS2CPacket.BlockEntityData> blockEntities) {}

    /** 直连拓扑：full 推送仅受 vanilla 通道可写性约束（网关会话背压已退役）。 */
    private static boolean isFullDeliveryChannelWritable(ServerPlayer player) {
        return true;
    }

    /**
     * 短窗口 ChunkHash 批次
     */

    /**
     * 每玩家 FIFO 任务队列。已封装批次在 {@link #queuedBatches} 中单独计数，
     * 因而不会把尚未到 tick 封批时机的任务错误地当作已排队批次。
     */


    /** 配置异常时保留历史安全默认值；正常配置值即每 tick 单批任务上限。 */
    static int normalizeMaxChunksPerTick(int configured) {
        return PullPacingValve.fullDeltaBudget(configured);
    }

    /** 未封批任务背压最多容纳十个满批，已封装批次另由 PlayerPushQueue 单独限额。 */
    private static int queueCapacity() {
        return MAX_QUEUED_BATCHES_PER_PLAYER * normalizeMaxChunksPerTick(
                HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick());
    }

    static final class PlayerPushQueue {
        private final java.util.ArrayDeque<PushTask> tasks = new java.util.ArrayDeque<>();
        /** 已到达但尚未获准进入主 FIFO 的推送义务，严格保持首次入队顺序。 */
        private final java.util.ArrayDeque<PushTask> overflow = new java.util.ArrayDeque<>();
        private int queuedBatches;

        /** 仅统计可在本 tick 封批的主 FIFO；背压判定不得把 overflow 当作可用槽位。 */
        synchronized int size() {
            return tasks.size();
        }

        synchronized int pendingCount() {
            return tasks.size() + overflow.size();
        }

        synchronized int overflowSize() {
            return overflow.size();
        }

        synchronized int queuedBatchCount() {
            return queuedBatches;
        }

        synchronized boolean isEmpty() {
            return tasks.isEmpty() && overflow.isEmpty();
        }

        /**
         * 把溢出 FIFO 队头回填至主 FIFO。调用方必须在接纳新任务前执行，禁止后来任务插队。
         */
        synchronized void promoteOverflow() {
            while (!overflow.isEmpty() && tasks.size() < queueCapacity()) {
                tasks.addLast(overflow.removeFirst());
            }
        }

        /** 预留一个已封装批次名额；满时本 tick 不从任务队列取任何任务。 */
        synchronized boolean tryReserveSealedBatch() {
            if (queuedBatches >= MAX_QUEUED_BATCHES_PER_PLAYER) {
                return false;
            }
            queuedBatches++;
            return true;
        }

        /** 封批未产出任何可消费工作时归还预留名额。 */
        synchronized void releaseSealedBatchReservation() {
            if (queuedBatches > 0) {
                queuedBatches--;
            }
        }

        /** 常驻消费者从通道取到批次后释放其排队名额。 */
        synchronized void dequeueSealedBatch() {
            if (queuedBatches > 0) {
                queuedBatches--;
            }
        }

        /**
         * 同柱已有任务视为成功（同类义务去重）。主 FIFO 满时改入 overflow，
         * 并在 overflow 未清空前拒绝后来任务直接进入主 FIFO，避免失败柱被后到任务反超。
         */
        synchronized boolean enqueue(PushTask task) {
            // 同柱已有任务视为成功（同类义务去重）
            if (findSameTask(tasks, task) != null || findSameTask(overflow, task) != null) {
                return true;
            }
            if (!overflow.isEmpty() || tasks.size() >= queueCapacity()) {
                if (overflow.size() >= MAX_OVERFLOW_TASKS_PER_PLAYER) {
                    Constants.LOG.warn("[PROCESS_QUEUE] Full-data overflow full (size={}); cannot stage chunk {}",
                            overflow.size(), task.pos());
                    return false;
                }
                overflow.addLast(task);
                return true;
            }
            addToPrimary(task);
            return true;
        }

        private static PushTask findSameTask(java.util.ArrayDeque<PushTask> queue, PushTask task) {
            for (PushTask existing : queue) {
                if (existing.pos().equals(task.pos()) && existing.dimension().equals(task.dimension())) {
                    return existing;
                }
            }
            return null;
        }

        private void addToPrimary(PushTask task) {
            tasks.addLast(task);
        }

        synchronized PushTask poll() {
            return tasks.pollFirst();
        }
        synchronized PushTask pollNearest(int centerX, int centerZ) {
            PushTask nearest = null;
            java.util.ArrayDeque<PushTask> source = null;
            int nearestDistance = Integer.MAX_VALUE;
            for (java.util.ArrayDeque<PushTask> candidateQueue :
                    java.util.List.of(tasks, overflow)) {
                for (PushTask candidate : candidateQueue) {
                    int distance = Math.abs(candidate.pos().x - centerX)
                            + Math.abs(candidate.pos().z - centerZ);
                    if (nearest == null || distance < nearestDistance) {
                        nearest = candidate;
                        nearestDistance = distance;
                        source = candidateQueue;
                    }
                }
            }
            if (nearest != null) {
                source.remove(nearest);
            }
            return nearest;
        }

        synchronized void clear() {
            tasks.clear();
            overflow.clear();
            queuedBatches = 0;
        }

        synchronized void removeIf(java.util.function.Predicate<PushTask> predicate) {
            tasks.removeIf(predicate);
            overflow.removeIf(predicate);
        }
    }
}





