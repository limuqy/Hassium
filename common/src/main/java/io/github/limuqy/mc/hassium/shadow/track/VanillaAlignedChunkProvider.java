package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 异步 Provider：acquire 只入队，网络 pull 由 {@link #drainPendingPulls} 限流发送。
 * <p>
 * 设计（对齐原版专用服观感，无客户端硬在途上限）：
 * <ul>
 *   <li>待拉队列无界；同一柱去重（pending / inflight）；</li>
 *   <li>发送限流（默认约对齐真服 {@code maxChunksPerTick} 吞吐）；</li>
 *   <li>发送前若已出权威窗 → <b>丢弃</b>（远离 tracking 不拉）；</li>
 *   <li>inflight 超时 → 冷却后允许再次入队，不硬拒绝 acquire。</li>
 * </ul>
 */
public final class VanillaAlignedChunkProvider implements ShadowChunkProvider {

    private static final VanillaAlignedChunkProvider INSTANCE = new VanillaAlignedChunkProvider();

    private final ConcurrentHashMap<Long, CompletableFuture<LevelChunk>> inflight =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> inflightStartMs =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> timeoutCooldownUntil =
            new ConcurrentHashMap<>();
    /** 待发送 pull：key → (dimension, pos)。无界队列语义；发送前检查窗外丢弃。 */
    private final ConcurrentHashMap<Long, PendingPull> pendingPulls =
            new ConcurrentHashMap<>();

    /** 发送节拍（影子主循环泵）。 */
    private static final long PULL_DRAIN_INTERVAL_MS = 50L;
    /** 每拍最多 C2S 柱数（~5/tick × 20tick/s ≈ 100/s，与真服下发同量级）。 */
    private static final int PULL_SENDS_PER_DRAIN = 5;
    /** 在途网络超时：超过则释放并冷却，避免永久 join。 */
    private static final long INFLIGHT_TIMEOUT_MS = 15_000L;
    /** 超时冷却：冷却内不立刻再 C2S，但仍可入队，冷却结束由 drain 重发。 */
    private static final long TIMEOUT_COOLDOWN_MS = 8_000L;

    private long lastDrainMs;

    private record PendingPull(String dimension, ChunkPos pos) {}

    private VanillaAlignedChunkProvider() {}

    public static VanillaAlignedChunkProvider getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean hasInFlight(String dimension, ChunkPos pos) {
        return pos != null && (inflight.containsKey(DimensionKey.key(dimension, pos.x, pos.z))
                || pendingPulls.containsKey(DimensionKey.key(dimension, pos.x, pos.z)));
    }

    /**
     * 入队 acquire：不立刻 C2S。有 material 直接 complete；窗外 fail；否则登记 pending。
     */
    @Override
    public CompletableFuture<LevelChunk> acquire(String dimension, ChunkPos pos,
                                                 AcquireReason reason) {
        if (dimension == null || pos == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("acquire args"));
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        CompletableFuture<LevelChunk> existing = inflight.get(key);
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        if (existing != null) {
            inflight.remove(key, existing);
        }
        Long coolUntil = timeoutCooldownUntil.get(key);
        long now = System.currentTimeMillis();
        if (coolUntil != null) {
            if (now < coolUntil) {
                // 冷却中：仍占队列位，到期后 drain 重发
                PendingPull prev = pendingPulls.get(key);
                if (prev == null) {
                    pendingPulls.put(key, new PendingPull(dimension, pos));
                }
                CompletableFuture<LevelChunk> cooling = new CompletableFuture<>();
                inflight.put(key, cooling);
                inflightStartMs.put(key, now);
                return cooling;
            }
            timeoutCooldownUntil.remove(key);
        }
        ShadowSeedServer existingServer = ShadowServerRegistry.getInstance().get();
        if (existingServer != null) {
            LevelChunk material = existingServer.injectedChunk(dimension, pos.x, pos.z);
            if (material != null) {
                completeAcquire(dimension, pos, material);
                return CompletableFuture.completedFuture(material);
            }
        }
        // seedGen 门控开：无 material 且无本地基线 → 不入队 network pull，
        // 交给影子 worldgen（A1-③）；避免与 authoritative FULL 抢跑把 locallyGenerated 打成 0。
        if (SeedGenExecutor.getInstance().isGenerationGateOpen()
                && !ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("seedgen-local: skip network pull " + pos));
        }
        ShadowTrackingSession session = ShadowTrackingSession.getInstance();
        if (session != null && !session.isInComputeDomain(pos.x, pos.z)) {
            io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat
                    .failSuspendedLoad(dimension, pos);
            pendingPulls.remove(key);
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_PROVIDER] skip acquire outside authority window ({}, {}) dim={}",
                    pos.x, pos.z, dimension);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("acquire outside authority window: " + pos));
        }
        CompletableFuture<LevelChunk> future = new CompletableFuture<>();
        CompletableFuture<LevelChunk> raced = inflight.putIfAbsent(key, future);
        if (raced != null && !raced.isDone()) {
            return raced;
        }
        if (raced != null) {
            inflight.replace(key, raced, future);
        }
        // 入队即登记（无硬上限）；真正 C2S 由 drainPendingPulls 限流 + 出窗丢弃
        pendingPulls.put(key, new PendingPull(dimension, pos));
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_PROVIDER] enqueue pull ({}, {}) dim={} reason={} pending={}",
                pos.x, pos.z, dimension, reason, pendingPulls.size());
        return future;
    }

    /**
     * 限流发送待拉队列（影子主循环调用）。出权威窗的柱直接丢弃。
     */
    public static void drainPendingPulls() {
        INSTANCE.drainPendingPulls0();
    }

    private void drainPendingPulls0() {
        long now = System.currentTimeMillis();
        if (now - lastDrainMs < PULL_DRAIN_INTERVAL_MS || pendingPulls.isEmpty()) {
            return;
        }
        lastDrainMs = now;
        ShadowTrackingSession session = ShadowTrackingSession.getInstance();
        ChunkPos center = session == null ? null : session.trackingCenter();
        List<PendingPull> batch = new ArrayList<>(pendingPulls.values());
        final ChunkPos c = center;
        if (c != null) {
            // S4：3×3 域分组排序（同域连片投递，组间按最近柱距离）——「服务端不会东投一柱，西投一柱」。
            // 这里才是真正的 C2S 出口，故分组必须落在本处（只在 acquire 侧排序会被本方法重排覆盖）。
            List<ChunkPos> positions = new ArrayList<>(batch.size());
            for (PendingPull pending : batch) {
                positions.add(pending.pos);
            }
            batch.sort(Comparator.comparing(p -> p.pos,
                    ShadowTrackingSession.domainComparator(positions, c.x, c.z)));
        }
        int sent = 0;
        int dropped = 0;
        int skippedCool = 0;
        for (PendingPull pending : batch) {
            if (sent >= PULL_SENDS_PER_DRAIN) {
                break;
            }
            long key = DimensionKey.key(pending.dimension, pending.pos.x, pending.pos.z);
            // 远距离：已出权威窗 → 丢弃，不再 C2S
            if (session != null && !session.isInComputeDomain(pending.pos.x, pending.pos.z)) {
                if (pendingPulls.remove(key, pending)) {
                    dropped++;
                    failSuspendedOnly(pending.dimension, pending.pos);
                    CompletableFuture<LevelChunk> f = inflight.remove(key);
                    inflightStartMs.remove(key);
                    if (f != null && !f.isDone()) {
                        f.completeExceptionally(
                                new IllegalStateException("pull dropped out of window: " + pending.pos));
                    }
                    try {
                        session.clearPullInFlight(pending.dimension, pending.pos);
                    } catch (Throwable ignored) {
                    }
                    // 出权威：清 compare AWAITING，OVD 环带本地源才不会被闸死
                    io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                            .clear(pending.dimension, pending.pos);
                }
                continue;
            }
            ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
            if (server != null) {
                LevelChunk material =
                        server.injectedChunk(pending.dimension, pending.pos.x, pending.pos.z);
                if (material != null) {
                    pendingPulls.remove(key, pending);
                    completeAcquire(pending.dimension, pending.pos, material);
                    continue;
                }
            }
            if (SeedGenExecutor.getInstance().isGenerationGateOpen()
                    && !ShadowLightCompute.hasLocalPullBaseline(pending.dimension, pending.pos)) {
                // seedGen 本地生成优先：丢掉 network pull 入队项
                if (pendingPulls.remove(key, pending)) {
                    dropped++;
                    CompletableFuture<LevelChunk> f = inflight.remove(key);
                    inflightStartMs.remove(key);
                    if (f != null && !f.isDone()) {
                        f.completeExceptionally(
                                new IllegalStateException("seedgen-local drop pull: " + pending.pos));
                    }
                    try {
                        session.clearPullInFlight(pending.dimension, pending.pos);
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }
            Long coolUntil = timeoutCooldownUntil.get(key);
            if (coolUntil != null && now < coolUntil) {
                skippedCool++;
                continue;
            }
            if (coolUntil != null) {
                timeoutCooldownUntil.remove(key);
            }
            CompletableFuture<LevelChunk> f = inflight.get(key);
            if (f != null && f.isDone()) {
                inflight.remove(key);
                f = null;
            }
            if (f == null) {
                f = new CompletableFuture<>();
                inflight.put(key, f);
            }
            inflightStartMs.put(key, now);
            boolean hasBaseline = ShadowLightCompute.hasLocalPullBaseline(pending.dimension, pending.pos);
            if (session != null) {
                if (!session.markPullInFlightForAcquire(pending.dimension, pending.pos, now)) {
                    session.clearPullInFlight(pending.dimension, pending.pos);
                    session.markPullInFlightForAcquire(pending.dimension, pending.pos, now);
                }
            }
            pendingPulls.remove(key, pending);
            ShadowChunkAcquire.pullOne(pending.dimension, pending.pos, hasBaseline);
            sent++;
        }
        if (sent > 0 || dropped > 0) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_PROVIDER] drain pulls sent={} droppedFar={} coolSkip={} pendingLeft={}",
                    sent, dropped, skippedCool, pendingPulls.size());
        }
    }

    private static void failSuspendedOnly(String dimension, ChunkPos pos) {
        try {
            io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat.failSuspendedLoad(dimension, pos);
        } catch (Throwable ignored) {
        }
    }

    /** inject 完成时清理 in-flight 与 pending。 */
    public static void completeAcquire(String dimension, ChunkPos pos, LevelChunk chunk) {
        if (dimension == null || pos == null) {
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        INSTANCE.pendingPulls.remove(key);
        CompletableFuture<LevelChunk> future = INSTANCE.inflight.remove(key);
        INSTANCE.inflightStartMs.remove(key);
        if (future != null && !future.isDone()) {
            if (chunk != null) {
                future.complete(chunk);
            } else {
                future.completeExceptionally(new IllegalStateException("acquire no chunk"));
            }
        }
        ShadowTrackingSession.getInstance().clearPullInFlight(dimension, pos);
        if (chunk != null) {
            io.github.limuqy.mc.hassium.protocol.ShadowPullClient.clearPullFailure(dimension, pos);
        }
    }

    public static void failAcquire(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        INSTANCE.pendingPulls.remove(key);
        INSTANCE.inflight.remove(key);
        INSTANCE.inflightStartMs.remove(key);
        try {
            ShadowTrackingSession.getInstance().clearPullInFlight(dimension, pos);
        } catch (Throwable ignored) {
        }
    }

    /** 在途超时扫描（可由 drain 前调用；无泵时 acquire 路径也会检查）。 */
    public static void noteInflightTimeout(long key, long nowMs) {
        INSTANCE.timeoutCooldownUntil.put(key, nowMs + TIMEOUT_COOLDOWN_MS);
    }

    public static void clearAll() {
        INSTANCE.inflight.clear();
        INSTANCE.inflightStartMs.clear();
        INSTANCE.timeoutCooldownUntil.clear();
        INSTANCE.pendingPulls.clear();
    }
}
