package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.storage.ShadowStorageHashes;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

/** Client-side compare-and-pull boundary for authoritative chunk data. */
public final class ShadowPullClient {
    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong();
    /** 请求模式仅覆盖在途响应；断连时 reset，达到上限时宁可放弃分类也不积压。 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> REQUEST_MODES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_TRACKED_REQUESTS = 1_024;

    private ShadowPullClient() {}

    /**
     * Compares any locally known shadow baseline with the authoritative chunk and pulls a FULL
     * only when it differs. Callers must deduplicate misses before calling this method.
     */
    public static void requestFull(String dimension, List<ChunkPos> chunks) {
        request(dimension, chunks, true);
    }

    /** Requests an unconditional authoritative FULL after a failed local/cache path. */
    public static void requestAuthoritativeFull(String dimension, List<ChunkPos> chunks) {
        request(dimension, chunks, false);
    }

    private static void request(String dimension, List<ChunkPos> chunks, boolean includeLocalBaseline) {
        if (dimension == null || dimension.isEmpty() || chunks == null || chunks.isEmpty()) {
            return;
        }
        for (int start = 0; start < chunks.size(); start += ShadowPullRequestC2SPacket.MAX_ENTRIES) {
            int end = Math.min(start + ShadowPullRequestC2SPacket.MAX_ENTRIES, chunks.size());
            long requestId = NEXT_REQUEST_ID.incrementAndGet();
            ShadowPullRequestC2SPacket request = fullRequest(dimension, requestId,
                    chunks.subList(start, end), includeLocalBaseline);
            if (request.entries().isEmpty()) {
                continue;
            }
            if (REQUEST_MODES.size() >= MAX_TRACKED_REQUESTS) {
                REQUEST_MODES.clear();
            }
            REQUEST_MODES.put(requestId, includeLocalBaseline);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            request.encode(buffer);
            Services.NETWORK_MANAGER.sendShadowPullRequest(buffer);
        }
    }

    static ShadowPullRequestC2SPacket fullRequest(String dimension, long requestId, List<ChunkPos> chunks) {
        return fullRequest(dimension, requestId, chunks, true);
    }
    static ShadowPullRequestC2SPacket fullRequest(String dimension, long requestId, List<ChunkPos> chunks,
                                                   boolean includeLocalBaseline) {
        List<ShadowPullRequestC2SPacket.Entry> entries = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            if (pos == null) {
                continue;
            }
            if (includeLocalBaseline) {
                entries.add(ShadowLightCompute.localPullEntry(dimension, pos));
            } else {
                entries.add(new ShadowPullRequestC2SPacket.Entry(pos.x, pos.z, 0L, List.of(), 0));
            }
        }
        return new ShadowPullRequestC2SPacket(dimension, 0L, requestId, entries);
    }

    /** 原版 tracking 首包的唯一入口：缓存存在时由 ShadowPull 取代 FULL，否则保留原版包建立基线。 */
    public static boolean handleNativeChunk(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet) {
        if (ClientChunkPipeline.getInstance().isApplyInProgress()) {
            return false;
        }
        if (packet == null || !io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientCacheEnabled()
                || !ShadowLightCompute.isEnabled()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return false;
        }
        String dimension = LevelCompat.getDimensionId(minecraft.level);
        ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
        if (dimension == null || !ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
            return false;
        }
        requestFull(dimension, List.of(pos));
        return true;
    }

    /** Applies compare-and-pull responses for the current client dimension. */
    public static void handleResponse(ShadowPullResponseS2CPacket response) {
        if (response == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null
                || !response.dimension().equals(LevelCompat.getDimensionId(minecraft.level))) {
            return;
        }
        Boolean comparedBaseline = REQUEST_MODES.remove(response.requestId());
        for (ShadowPullResponseS2CPacket.Result result : response.results()) {
            ChunkPos pos = new ChunkPos(result.chunkX(), result.chunkZ());
            if (result.kind() == ShadowPullResponseS2CPacket.Kind.FULL) {
                recordFullResult(comparedBaseline);
                if (!ClientChunkHandler.applyShadowPullFull(result.payload())) {
                    Constants.LOG.warn("[SHADOW_PULL] Failed to apply FULL ({}, {})", result.chunkX(), result.chunkZ());
                    requestAuthoritativeFull(response.dimension(), List.of(pos));
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.DELTA) {
                net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(result.payload()));
                try {
                    ShadowLightCompute.submitDelta(SectionDeltaS2CPacket.decode(buffer));
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.recordSectionDeltaRequestsSent(1);
                } catch (Throwable t) {
                    Constants.LOG.warn("[SHADOW_PULL] Failed to apply DELTA ({}, {}), retrying FULL",
                            result.chunkX(), result.chunkZ(), t);
                    requestAuthoritativeFull(response.dimension(), List.of(pos));
                } finally {
                    buffer.release();
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.UNCHANGED) {
                if (!ShadowLightCompute.publishCachedChunk(response.dimension(), pos)) {
                    Constants.LOG.warn("[SHADOW_PULL] Cache baseline unavailable for ({}, {}), retrying FULL",
                            result.chunkX(), result.chunkZ());
                    requestAuthoritativeFull(response.dimension(), List.of(pos));
                }
            } else {
                Constants.LOG.warn("[SHADOW_PULL] Request rejected for ({}, {}): {}",
                        result.chunkX(), result.chunkZ(), result.error());
                requestAuthoritativeFull(response.dimension(), List.of(pos));
            }
        }
    }

    private static void recordFullResult(Boolean comparedBaseline) {
        if (comparedBaseline == null) {
            return;
        }
        long bytes = io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES;
        if (comparedBaseline) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheStale(bytes);
        } else {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheMiss(bytes);
        }
    }

    public static void reset() {
        NEXT_REQUEST_ID.set(0L);
        REQUEST_MODES.clear();
    }
}
