package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.platform.Services;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

/** Client-side shadowPullV1 boundary for authoritative FULL fallback. */
public final class ShadowPullClient {
    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong();

    private ShadowPullClient() {}

    /**
     * Requests authoritative FULL chunks for one dimension. Requests are split at the protocol limit.
     * Callers must deduplicate misses before calling this method.
     */
    public static void requestFull(String dimension, List<ChunkPos> chunks) {
        if (dimension == null || dimension.isEmpty() || chunks == null || chunks.isEmpty()) {
            return;
        }
        for (int start = 0; start < chunks.size(); start += ShadowPullRequestC2SPacket.MAX_ENTRIES) {
            int end = Math.min(start + ShadowPullRequestC2SPacket.MAX_ENTRIES, chunks.size());
            ShadowPullRequestC2SPacket request = fullRequest(dimension, NEXT_REQUEST_ID.incrementAndGet(),
                    chunks.subList(start, end));
            if (request.entries().isEmpty()) {
                continue;
            }
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            request.encode(buffer);
            Services.NETWORK_MANAGER.sendShadowPullRequest(buffer);
        }
    }

    static ShadowPullRequestC2SPacket fullRequest(String dimension, long requestId, List<ChunkPos> chunks) {
        List<ShadowPullRequestC2SPacket.Entry> entries = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            if (pos != null) {
                entries.add(new ShadowPullRequestC2SPacket.Entry(pos.x, pos.z, 0L, List.of(), 0));
            }
        }
        return new ShadowPullRequestC2SPacket(dimension, 0L, requestId, entries);
    }

    /** Applies only FULL responses for the current client dimension. */
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
            if (result.kind() == ShadowPullResponseS2CPacket.Kind.FULL) {
                if (!ClientChunkHandler.applyShadowPullFull(result.payload())) {
                    Constants.LOG.warn("[SHADOW_PULL] Failed to apply FULL ({}, {})", result.chunkX(), result.chunkZ());
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.ERROR) {
                Constants.LOG.warn("[SHADOW_PULL] FULL rejected for ({}, {}): {}",
                        result.chunkX(), result.chunkZ(), result.error());
            } else {
                Constants.LOG.warn("[SHADOW_PULL] Unexpected {} response for FULL fallback ({}, {})",
                        result.kind(), result.chunkX(), result.chunkZ());
            }
        }
    }

    public static void reset() {
        NEXT_REQUEST_ID.set(0L);
    }
}
