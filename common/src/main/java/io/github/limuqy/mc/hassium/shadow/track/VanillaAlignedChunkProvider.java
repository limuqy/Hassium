package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 始终异步 Provider（§3.2）：无论有无盘/注入，选柱后一律向真服 compare 或权威 FULL。
 * <p>
 * 完成路径：Pull 响应 → {@code injectChunk}/{@code injectLoadedChunk} →
 * {@code ShadowChunkMapCompat.completeSuspendedLoad} 放行原版 holder 链。
 * 本类不直接 complete future（complete 由 inject 点负责，与悬置登记同源）。
 */
public final class VanillaAlignedChunkProvider implements ShadowChunkProvider {

    private static final VanillaAlignedChunkProvider INSTANCE = new VanillaAlignedChunkProvider();

    private final ConcurrentHashMap<Long, CompletableFuture<LevelChunk>> inflight =
            new ConcurrentHashMap<>();

    private VanillaAlignedChunkProvider() {}

    public static VanillaAlignedChunkProvider getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean hasInFlight(String dimension, ChunkPos pos) {
        return pos != null && inflight.containsKey(
                DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * 始终发起异步 acquire：in-flight 则 join；否则按基线 compare / 空基线 FULL。
     * 禁止同步读盘 Imposter。
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
        // 会话残留的已完成/失败 future：R2 重连 join 旧 future 会导致悬置 holder 永不完成
        if (existing != null) {
            inflight.remove(key, existing);
        }
        CompletableFuture<LevelChunk> future = new CompletableFuture<>();
        CompletableFuture<LevelChunk> raced = inflight.putIfAbsent(key, future);
        if (raced != null && !raced.isDone()) {
            return raced;
        }
        if (raced != null) {
            inflight.replace(key, raced, future);
        }
        ShadowTrackingSession session = ShadowTrackingSession.getInstance();
        // R4：会话未就绪 / 权威窗外禁止向真服 acquire（对齐原版 untrack 不发包）
        if (session != null && !session.isAuthorityPullEligible(pos.x, pos.z)) {
            inflight.remove(key, future);
            future.completeExceptionally(
                    new IllegalStateException("acquire outside authority window: " + pos));
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_PROVIDER] skip acquire outside authority window ({}, {}) dim={}",
                    pos.x, pos.z, dimension);
            return future;
        }
        if (!io.github.limuqy.mc.hassium.protocol.ShadowPullClient
                .isPullRetryAllowed(dimension, pos)) {
            // 冷却：清失败戳后立刻允许本轮（重连窗口不得整窗饿死）
            io.github.limuqy.mc.hassium.protocol.ShadowPullClient.clearPullFailure(dimension, pos);
        }
        boolean hasBaseline = ShadowLightCompute.hasLocalPullBaseline(dimension, pos);
        if (session != null && !session.markPullInFlightForAcquire(dimension, pos, System.currentTimeMillis())) {
            // 残留在途标记：清掉后重试一次，保证悬置 future 一定有 pull 在途
            session.clearPullInFlight(dimension, pos);
            if (!session.markPullInFlightForAcquire(dimension, pos, System.currentTimeMillis())) {
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[SHADOW_PROVIDER] in-flight mark busy ({}, {}) dim={}",
                        pos.x, pos.z, dimension);
            }
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_PROVIDER] acquire ({}, {}) dim={} baseline={} reason={}",
                pos.x, pos.z, dimension, hasBaseline, reason);
        ShadowChunkAcquire.pullOne(dimension, pos, hasBaseline);
        return future;
    }

    /** inject 完成时清理 in-flight；chunk 为 null 时仅清标记（卸载/失败）。 */
    public static void completeAcquire(String dimension, ChunkPos pos, LevelChunk chunk) {
        if (dimension == null || pos == null) {
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        CompletableFuture<LevelChunk> future = INSTANCE.inflight.remove(key);
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
        INSTANCE.inflight.remove(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static void clearAll() {
        INSTANCE.inflight.clear();
    }
}
