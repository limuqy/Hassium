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

/**
 * 统一 Compare+Pull 客户端边界：任何来源的区块数据首达（原版 tracking 首包 / 压缩通道 /
 * 网关剥光包）先与本地影子基线比较。服务端权威裁决 FULL / DELTA / UNCHANGED / ERROR。
 * <p>
 * 两种模式：
 * <ul>
 *   <li><b>拦截模式</b>（{@link #tryInterceptForCompare}）：网络数据已在手，但本地有 baseline。
 *       拦截后暂存网络数据 apply 回调（{@link PendingCompare}），等响应分类：
 *       UNCHANGED → 缓存回放；DELTA → 增量应用；FULL / ERROR / 超时 → 用已收网络数据
 *       （不浪费，响应载荷丢弃）。</li>
 *   <li><b>请求模式</b>（{@link #requestFull} 等）：客户端主动拉取（无网络数据在手），
 *       响应 FULL 用 {@link ClientChunkHandler#applyShadowPullFull} 应用。</li>
 * </ul>
 */
public final class ShadowPullClient {

    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong();
    /** 请求模式仅覆盖在途响应；断连时 reset，达到上限时宁可放弃分类也不积压。 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> REQUEST_MODES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_TRACKED_REQUESTS = 1_024;
    /** 拦截模式在途比较：pos key → 等待响应期间暂存网络数据 apply 回调（响应/超时后按分类执行）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, PendingCompare> PENDING_COMPARE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 拦截后响应未到达的最长等待；超时回退为网络数据注入，防区块黑洞。 */
    private static final long COMPARE_TIMEOUT_MS = 10_000L;
    /**
     * 已失败过一次的柱（复合键）：ERROR / UNCHANGED 无基线 / FULL 应用失败的重试上限。
     * 每柱每会话只重试一次——服务端 range 拒绝（柱在权威半径外）会无限复现，无上限重试
     * 会打出请求风暴拖垮客户端（pull9 实证：610 柱 × ~600 次重试）。二次失败即放弃，
     * 后续由玩家移动触发的 vanilla tracking 再自然触达。
     */
    private static final java.util.Set<Long> RETRIED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record PendingCompare(String dimension, long timestampMs, Runnable fallback) {}

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

    /**
     * 统一 Compare+Pull 拦截（原版包 / 压缩通道 / 网关剥光包共入口）。
     * <p>
     * 有本地基线且该 pos 本会话未在途 → 暂存网络数据 apply 回调并发出比较请求，
     * 返回 {@code true}（调用方必须丢弃网络数据，不得注入/落地）；响应或超时后按
     * 服务端裁决落地。无基线 / 已比较过 / 链路不可用 → 返回 {@code false}，调用方
     * 走原有网络注入路径。
     */
    public static boolean tryInterceptForCompare(String dimension, ChunkPos pos, Runnable networkApply) {
        if (dimension == null || pos == null || networkApply == null
                || !io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientCacheEnabled()
                || !ShadowLightCompute.isEnabled()) {
            return false;
        }
        if (!ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
            return false;
        }
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z);
        if (PENDING_COMPARE.putIfAbsent(key,
                new PendingCompare(dimension, System.currentTimeMillis(), networkApply)) != null) {
            // 已在途：重复推送直接丢弃，等响应落地
            return true;
        }
        requestFull(dimension, List.of(pos));
        return true;
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
        if (dimension == null) {
            return false;
        }
        return tryInterceptForCompare(dimension, pos,
                () -> io.github.limuqy.mc.hassium.network.seedgen.ShadowVanillaLightPipeline.submitVisible(
                        dimension, pos, packet,
                        io.github.limuqy.mc.hassium.network.ClientChunkHandler.TraceOrigin.SERVER_PUSH));
    }

    /**
     * pull FULL 载荷固定 zstd 解压（与 {@code ServerChunkPushManager#compressPullFullPayload}
     * 配对）。带宽压缩行锚点：原始 vs 压缩后线缆字节在此配对记账——通道压缩
     * = 包聚合帧 + shadow pull FULL（本处）+ DELTA 分段增量（SectionDeltaS2CPacket.decode）。
     * 解压失败返回空载荷 → applyShadowPullFull 失败 → 走 requestAuthoritativeFull 重试。
     */
    private static byte[] decompressPullFull(ShadowPullResponseS2CPacket.Result result) {
        byte[] compressed = result.payload();
        try {
            byte[] raw = io.github.limuqy.mc.hassium.compression.CompressionService.getInstance()
                    .decompress(compressed, Constants.NETWORK_COMPRESSION_ALGORITHM);
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordZstdDecompressed(
                    raw.length, compressed.length);
            // 原版等价 / 线缆字节记账收口到 pull 链（chunk_payload 通道退役观察期后归零）
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordChunkReceived(raw.length);
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordWireBytesReceived(compressed.length);
            return raw;
        } catch (Exception e) {
            Constants.LOG.warn("[SHADOW_PULL] FULL payload decompress failed ({}, {})",
                    result.chunkX(), result.chunkZ(), e);
            return new byte[0];
        }
    }

    /** 带重试上限的权威 FULL 重试：每柱每会话仅一次（见 {@link #RETRIED}）。 */
    private static void retryAuthoritativeFullOnce(String dimension, ChunkPos pos) {
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z);
        if (RETRIED.add(key)) {
            requestAuthoritativeFull(dimension, List.of(pos));
        }
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
            long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(response.dimension(), pos.x, pos.z);
            PendingCompare pending = PENDING_COMPARE.remove(key);
            if (result.kind() == ShadowPullResponseS2CPacket.Kind.FULL) {
                recordFullResult(comparedBaseline);
                if (pending != null) {
                    // 拦截模式：已收网络数据即权威，响应载荷丢弃（不解压）
                    pending.fallback().run();
                } else if (!ClientChunkHandler.applyShadowPullFull(decompressPullFull(result))) {
                    Constants.LOG.warn("[SHADOW_PULL] Failed to apply FULL ({}, {})", result.chunkX(), result.chunkZ());
                    retryAuthoritativeFullOnce(response.dimension(), pos);
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.DELTA) {
                net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(result.payload()));
                try {
                    ShadowLightCompute.submitDelta(SectionDeltaS2CPacket.decode(buffer));
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.recordSectionDeltaRequestsSent(1);
                    // 流量节省 actual 锚点：SectionDelta 线缆字节。decode 只记 zstd 对，
                    // 禁止在 recordSectionDeltaReceived 写 actual（防双重计数）。
                    // 漏记会让重连轮 actual=0 → 流量节省虚高 100%。
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.recordWireBytesReceived(
                            result.payload().length);
                } catch (Throwable t) {
                    Constants.LOG.warn("[SHADOW_PULL] Failed to apply DELTA ({}, {}), retrying FULL",
                            result.chunkX(), result.chunkZ(), t);
                    if (pending != null) {
                        pending.fallback().run();
                    } else {
                        retryAuthoritativeFullOnce(response.dimension(), pos);
                    }
                } finally {
                    buffer.release();
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.UNCHANGED) {
                if (!ShadowLightCompute.publishCachedChunk(response.dimension(), pos)) {
                    Constants.LOG.warn("[SHADOW_PULL] Cache baseline unavailable for ({}, {}), retrying FULL",
                            result.chunkX(), result.chunkZ());
                    if (pending != null) {
                        pending.fallback().run();
                    } else {
                        retryAuthoritativeFullOnce(response.dimension(), pos);
                    }
                }
            } else {
                Constants.LOG.warn("[SHADOW_PULL] Request rejected for ({}, {}): {}",
                        result.chunkX(), result.chunkZ(), result.error());
                if (pending != null) {
                    pending.fallback().run();
                }
                // 终态拒绝（range/unloaded/timeout 等）：失败即放弃，不重试——
                // 柱已不在服务端权威范围内，重试只会复现同一拒绝（pull9 风暴实证）
            }
        }
    }

    /** 主线程 tick：超时未响应的拦截回退为网络数据注入（防区块黑洞）。 */
    public static void expirePending() {
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<Long, PendingCompare> entry : PENDING_COMPARE.entrySet()) {
            if (now - entry.getValue().timestampMs() > COMPARE_TIMEOUT_MS
                    && PENDING_COMPARE.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().fallback().run();
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
        PENDING_COMPARE.clear();
        RETRIED.clear();
    }
}



