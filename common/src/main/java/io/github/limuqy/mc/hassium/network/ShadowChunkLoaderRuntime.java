package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side runtime coordinator for the shadowPullV1 loader.
 *
 * <p>This is deliberately small: desired-set ownership stays in
 * {@link ShadowChunkLoader}; transport and packet application remain platform
 * and pipeline responsibilities. The coordinator only turns the real client
 * position into bounded requests and completes the corresponding tickets.</p>
 */
public final class ShadowChunkLoaderRuntime {
    private static final int REQUEST_BATCH = 32;
    private static final long RESPONSE_TIMEOUT_MS = 5_000L;
    private static final ShadowChunkLoader LOADER = new ShadowChunkLoader();
    private static final Map<ShadowChunkLoader.ChunkKey, ShadowChunkLoader.LoadTicket> PENDING = new HashMap<>();
    private static final Map<ShadowChunkLoader.ChunkKey, Long> PENDING_DEADLINES = new HashMap<>();
    private static long requestId;
    private static String lastDimension;
    private static int lastCenterX;
    private static int lastCenterZ;
    private static int lastRadius = -1;

    private ShadowChunkLoaderRuntime() {}

    /** Called from the client tick on the main thread. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
        if (mc == null || mc.level == null || mc.player == null
                || !pipeline.isHassiumHandshakeDone()
                || io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().state()
                != io.github.limuqy.mc.hassium.network.core.NetworkCoreState.ACTIVE) {
            return;
        }
        long now = System.currentTimeMillis();
        for (var iterator = PENDING_DEADLINES.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (entry.getValue() > now) {
                continue;
            }
            ShadowChunkLoader.LoadTicket ticket = PENDING.remove(entry.getKey());
            iterator.remove();
            if (ticket != null) {
                LOADER.complete(ticket, false);
                Constants.LOG.warn("[SHADOW_PULL] response timeout epoch={} chunk=[{},{}]",
                        ticket.epoch(), ticket.key().chunkX(), ticket.key().chunkZ());
            }
        }
        String dimension = io.github.limuqy.mc.hassium.compat.LevelCompat.getDimensionId(mc.level);
        int centerX = mc.player.chunkPosition().x;
        int centerZ = mc.player.chunkPosition().z;
        int radius = Math.min(Math.max(0, mc.options.renderDistance().get()),
                io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().getMaxRenderDistance());
        if (!dimension.equals(lastDimension) || centerX != lastCenterX || centerZ != lastCenterZ || radius != lastRadius) {
            LOADER.updateView(dimension, centerX, centerZ, radius);
            for (ShadowChunkLoader.ChunkKey key : LOADER.desired()) {
                if (LOADER.role(key).orElse(null) == ShadowChunkRole.VISIBLE) {
                    ShadowLightCompute.promoteToVisible(dimension,
                            new net.minecraft.world.level.ChunkPos(key.chunkX(), key.chunkZ()));
                }
            }
            PENDING.clear();
            PENDING_DEADLINES.clear();
            lastDimension = dimension;
            lastCenterX = centerX;
            lastCenterZ = centerZ;
            lastRadius = radius;
        }

        List<ShadowChunkLoader.ChunkKey> desired = new ArrayList<>(LOADER.desired());
        desired.sort(Comparator
                .comparingInt((ShadowChunkLoader.ChunkKey key) ->
                        Math.max(Math.abs(key.chunkX() - centerX), Math.abs(key.chunkZ() - centerZ)))
                .thenComparingInt(key -> key.chunkX())
                .thenComparingInt(key -> key.chunkZ()));
        List<ShadowChunkLoader.LoadTicket> tickets = new ArrayList<>(REQUEST_BATCH);
        for (ShadowChunkLoader.ChunkKey key : desired) {
            if (tickets.size() >= REQUEST_BATCH) {
                break;
            }
            if (PENDING.containsKey(key)) {
                continue;
            }
            LOADER.beginLoad(key, false,
                    ClientChunkPipeline.getInstance().isServerSeedGenEnabled(), false)
                    .ifPresent(ticket -> {
                        PENDING.put(key, ticket);
                        PENDING_DEADLINES.put(key, now + RESPONSE_TIMEOUT_MS);
                        tickets.add(ticket);
                    });
        }
        if (tickets.isEmpty()) {
            return;
        }
        List<ShadowPullRequestC2SPacket.Entry> entries = new ArrayList<>(tickets.size());
        for (ShadowChunkLoader.LoadTicket ticket : tickets) {
            Long cachedHash = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(
                    dimension, new net.minecraft.world.level.ChunkPos(ticket.key().chunkX(), ticket.key().chunkZ()));
            entries.add(new ShadowPullRequestC2SPacket.Entry(
                    ticket.key().chunkX(), ticket.key().chunkZ(), cachedHash == null ? 0L : cachedHash,
                    List.of(), 0));
        }
        Constants.LOG.info("[SHADOW_PULL] request dimension={} epoch={} count={} center=[{},{}]", dimension,
                LOADER.epoch(), tickets.size(), centerX, centerZ);
        try {
            ShadowPullRequestC2SPacket request = new ShadowPullRequestC2SPacket(
                    dimension, LOADER.epoch(), ++requestId, entries);
            boolean sent = io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance()
                    .sendShadowPull(request);
            if (!sent) {
                throw new IllegalStateException("gateway outbound unavailable");
            }
        } catch (Throwable t) {
            for (ShadowChunkLoader.LoadTicket ticket : tickets) {
                PENDING.remove(ticket.key());
                PENDING_DEADLINES.remove(ticket.key());
                LOADER.complete(ticket, false);
            }
            Constants.LOG.warn("Hassium: failed to send shadowPullV1 request", t);
        }
    }

    /** Called on the client main thread after a response is decoded. */
    public static void handleResponse(ShadowPullResponseS2CPacket response) {
        String currentDimension = LOADER.dimension();
        if (response == null || currentDimension == null || !response.dimension().equals(currentDimension)
                || response.epoch() != LOADER.epoch()) {
            if (response != null) {
                Constants.LOG.warn("[SHADOW_PULL] response ignored dimension={} current={} epoch={} expected={}",
                        response.dimension(), currentDimension, response.epoch(), LOADER.epoch());
            }
            return;
        }
        for (ShadowPullResponseS2CPacket.Result result : response.results()) {
            ShadowChunkLoader.ChunkKey key = new ShadowChunkLoader.ChunkKey(
                    response.dimension(), result.chunkX(), result.chunkZ());
            ShadowChunkLoader.LoadTicket ticket = PENDING.remove(key);
            PENDING_DEADLINES.remove(key);
            if (ticket == null) {
                continue;
            }
            boolean success = result.kind() == ShadowPullResponseS2CPacket.Kind.UNCHANGED;
            if (result.kind() == ShadowPullResponseS2CPacket.Kind.FULL) {
                success = ClientChunkHandler.handleShadowPullPayload(
                        response.dimension(), result.chunkX(), result.chunkZ(), ticket.role(), result.payload());
                if (success) {
                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(response.dimension(),
                            new net.minecraft.world.level.ChunkPos(result.chunkX(), result.chunkZ()), result.chunkHash());
                }
            } else if (success) {
                ShadowLightCompute.accountCacheFullHit(response.dimension(),
                        new net.minecraft.world.level.ChunkPos(result.chunkX(), result.chunkZ()));
            }
            Constants.LOG.info("[SHADOW_PULL] apply result kind={} chunk=[{},{}] success={}",
                    result.kind(), result.chunkX(), result.chunkZ(), success);
            LOADER.complete(ticket, success);
        }
    }

    public static void reset() {
        PENDING.clear();
        PENDING_DEADLINES.clear();
        LOADER.reset();
        requestId = 0L;
        lastDimension = null;
        lastCenterX = 0;
        lastCenterZ = 0;
        lastRadius = -1;
    }

    static ShadowChunkLoader loaderForTest() {
        return LOADER;
    }
}
