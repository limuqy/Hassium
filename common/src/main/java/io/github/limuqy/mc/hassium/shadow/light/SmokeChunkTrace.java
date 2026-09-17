package io.github.limuqy.mc.hassium.shadow.light;

import io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession;

import io.github.limuqy.mc.hassium.utils.DimensionKey;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冒烟专用的逐柱链路账本：网络可见柱 → 影子注入 → 回传 ready → 客户端落地 → 首次网格编译。
 * <p>
 * 另收两类耗时（同为冒烟专用）：
 * <ul>
 *   <li><b>本地 worldgen</b>：{@code MixinChunkMap} 放行原版生成时记起点，
 *       {@code ShadowTrackingSession.onChunkMaterialized} 物化时结算 → 单柱生成耗时分布。</li>
 *   <li><b>端到端</b>：复用已有的 networkReceived / clientApplied 时间戳，
 *       在 {@link #snapshot} 内配对算「网络可见→客户端落地」链路延迟与「首柱→第 N 柱」可见进度。</li>
 * </ul>
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
    /** 本地 worldgen 起点（chunk 复合键 → ns）；放行原版生成时登记。 */
    private static final ConcurrentHashMap<Long, Long> WORLDGEN_START_AT_NS = new ConcurrentHashMap<>();
    /** 本地 worldgen 单柱耗时（chunk 复合键 → ns）；物化时结算。 */
    private static final ConcurrentHashMap<Long, Long> WORLDGEN_NS = new ConcurrentHashMap<>();
    /** 起点登记总次数（诊断：0 = 起点钩子未触达，非采样失败）。 */
    private static final java.util.concurrent.atomic.AtomicLong WORLDGEN_STARTS =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * 会话起点（该轮／该维度 trace 重置时刻 = 进服前一刻）。
     * 用于「进服 → 所有区块应用完成」总耗时：{@code max(clientAppliedAt) - 本值}。
     */
    private static volatile long sessionStartAtMs;

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
        if (!isDeliveryCandidate(dimension, pos)) {
            return;
        }
        record(NETWORK_RECEIVED, NETWORK_RECEIVED_AT_MS, dimension, pos);
    }

    public static void recordShadowInjected(String dimension, ChunkPos pos) {
        if (!isDeliveryCandidate(dimension, pos)) {
            return;
        }
        record(SHADOW_INJECTED, null, dimension, pos);
    }

    public static void recordShadowReady(String dimension, ChunkPos pos) {
        if (!isDeliveryCandidate(dimension, pos)) {
            return;
        }
        record(SHADOW_READY, null, dimension, pos);
    }

    /**
     * 交付候选：OVD 开启时全量记录；OVD 冻结时只记权威可见窗内柱。
     * <p>
     * serverViewDistance≤0（重连 join 窗、VD 尚未由登录包写入）时**不记录**：
     * 否则 R2 冷启动窗口会把整盘注入记成 expected，客户端随后只驻留 VD10 子集，
     * analyzer 报 TRACE_EXPECTED_NOT_PRESENT 假阳性（phaseA2 实证 gap=1076）。
     */
    private static boolean isDeliveryCandidate(String dimension, ChunkPos pos) {
        if (!ENABLED || pos == null) {
            return false;
        }
        try {
            if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                    .isViewDistanceExtensionEnabled()) {
                return true;
            }
        } catch (Throwable ignored) {
            // 配置不可读：按冻结处理，继续走会话门
        }
        // OVD 冻结：VD≤0 或虚拟玩家未就绪时**不记录**。
        // isDeliverableToClient 在 center==null 时会放行全部（交付门语义），
        // 若用作 trace 候选门，R2 join 窗口会把整盘注入记成 expected
        //（phaseA4：tracked=1384 vs present=511）。
        ShadowTrackingSession session = ShadowTrackingSession.getInstance();
        if (ShadowTrackingSession.serverViewDistance() <= 0 || !session.hasVirtualPlayer()) {
            return false;
        }
        return ShadowTrackingSession.isDeliverableToClient(pos.x, pos.z);
    }

    public static void recordClientApplied(String dimension, ChunkPos pos) {
        record(CLIENT_APPLIED, CLIENT_APPLIED_AT_MS, dimension, pos);
    }

    public static void recordMeshCompiled(String dimension, ChunkPos pos) {
        record(MESH_COMPILED, null, dimension, pos);
    }

    /**
     * 本地 worldgen 起点登记（影子放行原版生成的那一刻）。
     * 同柱重复调用（scheduleChunkLoad 会按状态多次进入）只记第一次。
     */
    public static void recordWorldgenStart(String dimension, ChunkPos pos) {
        if (!ENABLED || pos == null || !DimensionKey.isCacheableDimension(dimension)) {
            return;
        }
        WORLDGEN_START_AT_NS.putIfAbsent(DimensionKey.key(dimension, pos.x, pos.z), System.nanoTime());
        WORLDGEN_STARTS.incrementAndGet();
    }

    /**
     * 本地 worldgen 物化结算：只有登记过起点、且被判定为「本会话本地生成」的柱才计入样本。
     * 依赖柱 / 磁盘命中柱 / 网络注入柱无起点，直接忽略。
     */
    public static void recordWorldgenEnd(String dimension, ChunkPos pos) {
        if (!ENABLED || pos == null || !DimensionKey.isCacheableDimension(dimension)) {
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        Long startNs = WORLDGEN_START_AT_NS.remove(key);
        if (startNs == null) {
            return;
        }
        WORLDGEN_NS.put(key, Math.max(0L, System.nanoTime() - startNs));
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
        WORLDGEN_START_AT_NS.clear();
        WORLDGEN_NS.clear();
        WORLDGEN_STARTS.set(0L);
        markSessionStart();
    }

    /**
     * 会话起点显式置位：客户端确认已进服的那一刻（{@code ScenarioEngine} join 步骤判定完成时调用）。
     * <p>
     * 单轮场景（modcompat / seedgen）全程不调 {@link #reset()}，起点只能靠这里置位，
     * 否则 {@link Snapshot#sessionToAllMs()} 恒为 -1（死字段）。{@link #reset()} 复用它，
     * 使切维 / 断连后重连的会话起点跟随重置时刻。
     */
    public static void markSessionStart() {
        if (!ENABLED) {
            return;
        }
        sessionStartAtMs = System.currentTimeMillis();
    }

    public static Snapshot snapshot(String dimension) {
        if (!ENABLED || dimension == null || dimension.isBlank()) {
            return Snapshot.empty();
        }
        Set<Long> networkReceived = positionsForDimension(NETWORK_RECEIVED, dimension);
        Set<Long> shadowInjected = positionsForDimension(SHADOW_INJECTED, dimension);
        Set<Long> shadowReady = positionsForDimension(SHADOW_READY, dimension);
        Set<Long> clientApplied = positionsForDimension(CLIENT_APPLIED, dimension);
        Map<Long, Long> networkReceivedAtMs = timesForDimension(NETWORK_RECEIVED_AT_MS, dimension);
        Map<Long, Long> clientAppliedAtMs = timesForDimension(CLIENT_APPLIED_AT_MS, dimension);
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
                networkReceivedAtMs,
                clientAppliedAtMs,
                sessionStartAtMs,
                sessionToAllMs(clientAppliedAtMs),
                WORLDGEN_STARTS.get(),
                WORLDGEN_START_AT_NS.size(),
                latency(worldgenSamplesMs(dimension)),
                revealLatency(clientAppliedAtMs)
        );
    }

    /**
     * 「进服 → 所有区块应用完成」总耗时（ms）= 最后一柱 applied − 会话起点。
     * 起点取该轮 trace 重置时刻（进服前一刻），两臂同相位置位，可比。
     * 无 applied 样本或未置起点返回 -1。
     */
    private static long sessionToAllMs(Map<Long, Long> appliedAtMs) {
        if (appliedAtMs.isEmpty() || sessionStartAtMs <= 0L) {
            return -1L;
        }
        long last = Long.MIN_VALUE;
        for (long atMs : appliedAtMs.values()) {
            last = Math.max(last, atMs);
        }
        return Math.max(0L, last - sessionStartAtMs);
    }

    /** 本地 worldgen 单柱耗时样本（ms，取本维度）。 */
    private static List<Long> worldgenSamplesMs(String dimension) {
        List<Long> samples = new ArrayList<>();
        for (var entry : WORLDGEN_NS.entrySet()) {
            if (dimension.equals(DimensionKey.dimensionOf(entry.getKey()))) {
                samples.add(entry.getValue() / 1_000_000L);
            }
        }
        return samples;
    }

    /** 可见进度：以本维度最早一个 networkReceived 为基线，统计各 applied 柱落地的相对偏移。 */
    private static Latency revealLatency(Map<Long, Long> appliedAtMs) {
        if (appliedAtMs.isEmpty()) {
            return Latency.EMPTY;
        }
        long baseline = Long.MAX_VALUE;
        for (long atMs : appliedAtMs.values()) {
            baseline = Math.min(baseline, atMs);
        }
        List<Long> samples = new ArrayList<>(appliedAtMs.size());
        for (long atMs : appliedAtMs.values()) {
            samples.add(atMs - baseline);
        }
        return latency(samples);
    }

    /** 最近秩百分位（ms）；空样本返回 {@link Latency#EMPTY}。包可见供单测。 */
    public static Latency latency(List<Long> samplesMs) {
        if (samplesMs.isEmpty()) {
            return Latency.EMPTY;
        }
        samplesMs.sort(Comparator.naturalOrder());
        int n = samplesMs.size();
        return new Latency(n, samplesMs.get((n - 1) * 50 / 100), samplesMs.get((n - 1) * 95 / 100),
                samplesMs.get(n - 1));
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

    /** 延迟分布（ms 整数；最近秩百分位）。 */
    public record Latency(long count, long p50Ms, long p95Ms, long maxMs) {
        static final Latency EMPTY = new Latency(0, 0, 0, 0);
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
            java.util.Map<Long, Long> clientAppliedAtMs,
            /** 会话起点（该轮 trace 重置时刻 = 进服前一刻）；未置位为 0。 */
            long sessionStartAtMs,
            /** 「进服 → 所有区块应用完成」= 最后一柱 applied − 会话起点；无样本为 -1。 */
            long sessionToAllMs,
            /** 本地 worldgen 起点登记次数（诊断：0 = 起点钩子未触达，而非采样失败）。 */
            long worldgenStarts,
            /** 起点已登记但尚未物化的柱数（诊断）。 */
            int worldgenPending,
            /** 本地 worldgen 单柱耗时（影子放行 → 物化）。 */
            Latency worldgenLatency,
            /** 可见进度（本维度首柱网络可见 → 各柱落地偏移）。 */
            Latency revealLatency
    ) {
        private static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                    0L, -1L, 0L, 0, Latency.EMPTY, Latency.EMPTY);
        }
    }
}
