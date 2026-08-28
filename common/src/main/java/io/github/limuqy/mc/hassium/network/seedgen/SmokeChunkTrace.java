package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.utils.DimensionKey;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冒烟专用的逐柱链路账本：网络可见柱 → 影子注入 → 回传 ready → 客户端落地 → 首次网格编译。
 * <p>
 * 仅 {@code hassium.smokeTest.probeDir} 存在时记录；生产运行不分配集合、不改变区块流水线。
 */
public final class SmokeChunkTrace {
    private static final boolean ENABLED = isEnabled();

    private static final Set<Long> NETWORK_RECEIVED = newTraceSet();
    private static final Set<Long> SHADOW_INJECTED = newTraceSet();
    private static final Set<Long> SHADOW_READY = newTraceSet();
    private static final Set<Long> CLIENT_APPLIED = newTraceSet();
    private static final Set<Long> MESH_COMPILED = newTraceSet();
    private static final ConcurrentHashMap<Long, Long> NETWORK_RECEIVED_AT_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> CLIENT_APPLIED_AT_MS = new ConcurrentHashMap<>();

    private SmokeChunkTrace() {
    }
    private static boolean isEnabled() {
        String probeDir = System.getProperty("hassium.smokeTest.probeDir");
        return probeDir != null && !probeDir.isBlank();
    }

    private static Set<Long> newTraceSet() {
        return ENABLED ? ConcurrentHashMap.newKeySet() : Set.of();
    }

    public static void recordNetworkReceived(String dimension, ChunkPos pos) {
        record(NETWORK_RECEIVED, NETWORK_RECEIVED_AT_MS, dimension, pos);
    }

    public static void recordShadowInjected(String dimension, ChunkPos pos) {
        record(SHADOW_INJECTED, null, dimension, pos);
    }

    public static void recordShadowReady(String dimension, ChunkPos pos) {
        record(SHADOW_READY, null, dimension, pos);
    }

    public static void recordClientApplied(String dimension, ChunkPos pos) {
        record(CLIENT_APPLIED, CLIENT_APPLIED_AT_MS, dimension, pos);
    }

    public static void recordMeshCompiled(String dimension, ChunkPos pos) {
        record(MESH_COMPILED, null, dimension, pos);
    }

    public static void reset() {
        if (!ENABLED) {
            return;
        }
        NETWORK_RECEIVED.clear();
        SHADOW_INJECTED.clear();
        SHADOW_READY.clear();
        CLIENT_APPLIED.clear();
        MESH_COMPILED.clear();
        NETWORK_RECEIVED_AT_MS.clear();
        CLIENT_APPLIED_AT_MS.clear();
    }

    public static Snapshot snapshot(String dimension) {
        if (!ENABLED || dimension == null || dimension.isBlank()) {
            return Snapshot.empty();
        }
        Set<Long> networkReceived = positionsForDimension(NETWORK_RECEIVED, dimension);
        Set<Long> shadowInjected = positionsForDimension(SHADOW_INJECTED, dimension);
        Set<Long> shadowReady = positionsForDimension(SHADOW_READY, dimension);
        Set<Long> clientApplied = positionsForDimension(CLIENT_APPLIED, dimension);
        return new Snapshot(
                toPositions(networkReceived),
                toPositions(shadowInjected),
                toPositions(shadowReady),
                toPositions(clientApplied),
                toPositions(MESH_COMPILED, dimension),
                toPositions(difference(networkReceived, shadowInjected)),
                toPositions(difference(shadowInjected, shadowReady)),
                toPositions(difference(shadowReady, clientApplied)),
                toPositions(difference(clientApplied, positionsForDimension(MESH_COMPILED, dimension))),
                timesForDimension(NETWORK_RECEIVED_AT_MS, dimension),
                timesForDimension(CLIENT_APPLIED_AT_MS, dimension)
        );
    }

    private static void record(Set<Long> destination, ConcurrentHashMap<Long, Long> times,
                               String dimension, ChunkPos pos) {
        if (ENABLED && pos != null && DimensionKey.isCacheableDimension(dimension)) {
            long key = DimensionKey.key(dimension, pos.x, pos.z);
            destination.add(key);
            if (times != null) {
                times.putIfAbsent(key, System.currentTimeMillis());
            }
        }
    }

    private static Set<Long> positionsForDimension(Set<Long> source, String dimension) {
        Set<Long> positions = ConcurrentHashMap.newKeySet();
        for (long key : source) {
            if (dimension.equals(DimensionKey.dimensionOf(key))) {
                positions.add(ChunkPos.asLong(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)));
            }
        }
        return positions;
    }
    private static java.util.Map<Long, Long> timesForDimension(
            ConcurrentHashMap<Long, Long> source, String dimension) {
        java.util.Map<Long, Long> result = new java.util.HashMap<>();
        for (var entry : source.entrySet()) {
            if (dimension.equals(DimensionKey.dimensionOf(entry.getKey()))) {
                result.put(ChunkPos.asLong(DimensionKey.chunkXOf(entry.getKey()),
                        DimensionKey.chunkZOf(entry.getKey())), entry.getValue());
            }
        }
        return java.util.Map.copyOf(result);
    }

    private static Set<Long> difference(Set<Long> left, Set<Long> right) {
        Set<Long> result = ConcurrentHashMap.newKeySet();
        result.addAll(left);
        result.removeAll(right);
        return result;
    }

    private static List<ChunkPos> toPositions(Set<Long> positions) {
        List<ChunkPos> result = new ArrayList<>(positions.size());
        for (long packed : positions) {
            result.add(new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)));
        }
        result.sort(Comparator.comparingInt((ChunkPos pos) -> pos.z).thenComparingInt(pos -> pos.x));
        return List.copyOf(result);
    }

    private static List<ChunkPos> toPositions(Set<Long> positions, String dimension) {
        return toPositions(positionsForDimension(positions, dimension));
    }

    public record Snapshot(
            List<ChunkPos> networkReceived,
            List<ChunkPos> shadowInjected,
            List<ChunkPos> shadowReady,
            List<ChunkPos> clientApplied,
            List<ChunkPos> meshCompiled,
            List<ChunkPos> receivedNotInjected,
            List<ChunkPos> injectedNotReady,
            List<ChunkPos> readyNotApplied,
            List<ChunkPos> appliedNotMeshed,
            java.util.Map<Long, Long> networkReceivedAtMs,
            java.util.Map<Long, Long> clientAppliedAtMs
    ) {
        private static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        }
    }
}
