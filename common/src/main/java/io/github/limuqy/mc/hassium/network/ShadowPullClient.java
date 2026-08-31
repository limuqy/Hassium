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
            ShadowPullRequestC2SPacket request = fullRequest(dimension, NEXT_REQUEST_ID.incrementAndGet(),
                    chunks.subList(start, end), includeLocalBaseline);
            if (request.entries().isEmpty()) {
                continue;
            }
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
            Long localHash = includeLocalBaseline ? ShadowStorageHashes.get(dimension, pos) : null;
            entries.add(new ShadowPullRequestC2SPacket.Entry(pos.x, pos.z,
                    localHash == null ? 0L : localHash, List.of(), 0));
        }
        return new ShadowPullRequestC2SPacket(dimension, 0L, requestId, entries);
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
        for (ShadowPullResponseS2CPacket.Result result : response.results()) {
            ChunkPos pos = new ChunkPos(result.chunkX(), result.chunkZ());
            if (result.kind() == ShadowPullResponseS2CPacket.Kind.FULL) {
                if (!ClientChunkHandler.applyShadowPullFull(result.payload())) {
                    Constants.LOG.warn("[SHADOW_PULL] Failed to apply FULL ({}, {})", result.chunkX(), result.chunkZ());
                    requestAuthoritativeFull(response.dimension(), List.of(pos));
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.UNCHANGED) {
                if (!ShadowLightCompute.publishCachedChunk(response.dimension(), pos)) {
                    Constants.LOG.warn("[SHADOW_PULL] Cache baseline unavailable for ({}, {}), retrying FULL",
                            result.chunkX(), result.chunkZ());
                    requestAuthoritativeFull(response.dimension(), List.of(pos));
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.ERROR) {
                Constants.LOG.warn("[SHADOW_PULL] Request rejected for ({}, {}): {}",
                        result.chunkX(), result.chunkZ(), result.error());
            } else {
                // Section delta uses SectionHashRequestC2SPacket/SectionDeltaS2CPacket. A DELTA here is
                // a protocol mismatch, so preserve correctness by requesting the authoritative FULL.
                Constants.LOG.warn("[SHADOW_PULL] Unexpected {} response for ({}, {}), retrying FULL",
                        result.kind(), result.chunkX(), result.chunkZ());
                requestAuthoritativeFull(response.dimension(), List.of(pos));
            }
        }
    }

    public static void reset() {
        NEXT_REQUEST_ID.set(0L);
    }
}
