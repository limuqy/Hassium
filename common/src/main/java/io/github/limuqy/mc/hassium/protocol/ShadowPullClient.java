package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.platform.client.TraceOrigin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.client.ClientChunkHandler;
import io.github.limuqy.mc.hassium.client.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.protocol.handshake.ClientLoginNegotiation;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes;
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
    /**
     * 请求模式（requestId → 是否带本地基线），仅覆盖在途响应；断连时清空。
     * <p>
     * 【为什么是有界 LRU，而不是「满了整体 clear」】旧实现一旦达到上限就 {@code clear()}
     * 整表：进服一批就上千请求（VD20 = 1705 柱），响应回来时回查**恒 miss**
     * ⇒ {@code comparedBaseline} 恒 {@code null} ⇒ {@code cacheMiss}/{@code cacheStale}
     * **结构性恒 0**（2026-09-20 实测 1750 次 FULL 全部 withBaseline=0，逼得排查只能换口径）。
     * 改成逐出最旧：容量内**必命中**，代价只是极旧的在途请求退化为「模式未知」。
     */
    private static final java.util.Map<Long, Boolean> REQUEST_MODES =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<>(256, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                            return size() > MAX_TRACKED_REQUESTS;
                        }
                    });
    private static final int MAX_TRACKED_REQUESTS = 8_192;
    /**
     * C2S 载荷预算余量：vanilla 的 32767 限的是**载荷本身**，而各加载器封装
     * （forge `ShadowPullRequestWrapper` / neoforge `ByteArrayPayload`）可能再包一层。
     * 留 1 KiB 使"我们算出的字节数"与"线上载荷"之间不擦边。
     */
    private static final int PAYLOAD_HEADROOM_BYTES = 1_024;
    /** 拦截模式在途比较：pos key → 等待响应期间暂存网络数据 apply 回调（响应/超时后按分类执行）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, PendingCompare> PENDING_COMPARE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 拦截后响应未到达的最长等待；超时回退为网络数据注入，防区块黑洞。 */
    private static final long COMPARE_TIMEOUT_MS = 10_000L;
    /**
     * Pull 失败冷却：RANGE/ERROR 等拒绝后短冷却，而非会话级一次失败永久放弃。
     * <p>
     * 移动时校验中心与请求时刻错位会打出 range 拒绝；会话级 {@code RETRIED} 会
     * 让该柱在 60s sweep 在途锁之外再也发不出去。冷却期内跳过，到期后
     * sweep / 权威选柱可再试。
     */
    private static final long RETRY_COOLDOWN_MS = 3_000L;
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> lastFailAtMs =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record PendingCompare(String dimension, long timestampMs, Runnable fallback) {}

    private ShadowPullClient() {}

    /** 记录失败并释放影子在途锁，使冷却结束后可被再次选中。 */
    public static void notePullFailure(String dimension, ChunkPos pos) {
        if (pos == null) {
            return;
        }
        String dim = dimension != null ? dimension : null;
        if (dim == null) {
            return;
        }
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dim, pos.x, pos.z);
        lastFailAtMs.put(key, System.currentTimeMillis());
        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance()
                .clearPullInFlight(dim, pos);
    }

    /** 冷却是否已过（可再次发 pull）。 */
    public static boolean isPullRetryAllowed(String dimension, ChunkPos pos) {
        if (pos == null || dimension == null) {
            return true;
        }
        Long at = lastFailAtMs.get(
                io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z));
        return at == null || System.currentTimeMillis() - at >= RETRY_COOLDOWN_MS;
    }

    /** 成功注入/交付后清除失败冷却；dimension/pos 为 null 时清空全部（会话 reset）。 */
    public static void clearPullFailure(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            lastFailAtMs.clear();
            return;
        }
        lastFailAtMs.remove(
                io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z));
    }

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
        io.github.limuqy.mc.hassium.utils.DebugLogger.info(
                io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.NETWORK,
                "[DIAG] ShadowPullClient.request dim={} chunks={} baseline={}",
                dimension, chunks == null ? -1 : chunks.size(), includeLocalBaseline);
        if (dimension == null || dimension.isEmpty() || chunks == null || chunks.isEmpty()) {
            return;
        }
        if (includeLocalBaseline) {
            compareRequests.addAndGet(chunks.size());
            materializeForCompare(dimension, chunks);
        } else {
            authoritativeRequests.addAndGet(chunks.size());
        }
        List<ShadowPullRequestC2SPacket.Entry> entries =
                fullRequest(dimension, 0L, chunks, includeLocalBaseline).entries();
        // 按**编码后字节数**分批，而不是只按条数：单柱最多带 64 段 × PLANE_COUNT(48) 个 int 分量，
        // MAX_ENTRIES 界不住载荷。超 32 KiB 的 C2S 载荷会被 vanilla 拒收并踢掉客户端
        // （实测：1.20.1 首批 384 条声明聚合出 count=160 的 compare 请求即触发）。
        int budget = ShadowPullRequestC2SPacket.MAX_PAYLOAD_BYTES - PAYLOAD_HEADROOM_BYTES;
        for (List<ShadowPullRequestC2SPacket.Entry> batch
                : ShadowPullRequestC2SPacket.batchesByEncodedSize(dimension, entries, budget)) {
            long requestId = NEXT_REQUEST_ID.incrementAndGet();
            REQUEST_MODES.put(requestId, includeLocalBaseline);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            new ShadowPullRequestC2SPacket(dimension, 0L, requestId, batch).encode(buffer);
            Services.NETWORK_MANAGER.sendShadowPullRequest(buffer);
        }
    }

    /**
     * compare 前把「盘上有柱、内存没柱」的目标先读盘物化。
     * <p>
     * 必要性：{@code localPullEntry} 只有在内存里有活柱时才能带上**逐段/平面**基线；
     * 只带柱级 hash 时服务端 {@code SectionDeltaPlanner} 会把每个非空段判成
     * {@code clientAir != serverAir} → 全段 FULL → 占比 100% ≥75% → 整柱回退。
     * 收口点选在这里：所有 compare 路径（tracking 泵 / 权威提示 / 原生拦截）都经
     * {@link #request}，改一处即全覆盖。
     * <p>
     * <b>渲染线程直接跳过</b>：{@code ChunkAuthority→requestFull} 会从渲染线程进来，
     * 而这里要解压整柱（es3 冻结的老路）。跳过后该柱退化为「只有柱级 hash」（= 修复前行为），
     * 绝不阻塞渲染。
     */
    private static void materializeForCompare(String dimension, List<ChunkPos> chunks) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.isSameThread()) {
            return;
        }
        for (ChunkPos pos : chunks) {
            if (pos == null) {
                continue;
            }
            io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer shadow =
                    io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
            if (shadow == null) {
                return;
            }
            if (shadow.injectedChunk(dimension, pos.x, pos.z) != null) {
                continue;
            }
            ShadowLightCompute.materializeFromDiskForCompare(dimension, pos);
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
     * 该 pos 本会话未在途 → 暂存网络数据 apply 回调并发出比较请求，返回
     * {@code true}（调用方必须丢弃网络数据，不得注入/落地）；响应或超时后按
     * 服务端裁决落地。有本地基线走 hash 比较（UNCHANGED/DELTA/FULL 裁决），
     * 无本地基线走空基线请求（服务端必答 FULL）——chunk_payload 通道退役后的
     * 统一权威路径。链路不可用（未协商 ShadowPull / cache 关 / 原版服务端）
     * → 返回 {@code false}，调用方走原有网络注入路径。
     */
    public static boolean tryInterceptForCompare(String dimension, ChunkPos pos, Runnable networkApply) {
        if (dimension == null || pos == null || networkApply == null
                || !io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientCacheEnabled()
                || !ShadowLightCompute.isEnabled()) {
            return false;
        }
        // 仅协商过 ShadowPull 的服务端会应答比较请求；原版服务端 / 未握手场景
        // 放行网络注入（拦截后无人应答会挂起 10s 才超时回退，区块延迟不可接受）。
        if (!LoginCaps.has(ClientLoginNegotiation.current(), LoginCaps.SHADOW_PULL)) {
            return false;
        }
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z);
        if (PENDING_COMPARE.putIfAbsent(key,
                new PendingCompare(dimension, System.currentTimeMillis(), networkApply)) != null) {
            // 已在途：重复推送直接丢弃，等响应落地
            return true;
        }
        if (ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
            // 有本地基线：发出 hash 比较请求（服务端按 UNCHANGED/DELTA/FULL 裁决）
            requestFull(dimension, List.of(pos));
        } else if (io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance() != null
                && io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor.getInstance().isGenerationGateOpen()
                && io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance()
                        .isInComputeDomain(pos.x, pos.z)) {
            // A1-③（handoff-2026-09-18 §1）：seedGen 开 + 无缓存 → 本地生成作基线 → compare。
            // 拦截路径【只挂起，不驱动】：生成由影子主循环泵的 SEEDGEN_LOCAL 分支统一驱动
            // （pin FORCED 票等操作仅影子线程安全，渲染线程不得触碰——2026-09-21 实测
            // 渲染线程 pin 票 + C2ME rewrites-chunk-system 并发 DistanceManager 更新
            // → LeveledPriorityQueue 结构损坏崩溃）。泵不覆盖的柱（窗外）由 10s 超时
            // 回退 networkApply 兜底，数据不丢。
            seedGenIntercepted.incrementAndGet();
        } else {
            // 无本地基线且本地生成未接管（gate 关 / 窗外 / 在途满）：空基线请求，
            // 服务端必答 FULL——统一 Compare+Pull，不放行未经服务端裁决的网络注入
            requestAuthoritativeFull(dimension, List.of(pos));
        }
        return true;
    }

    /** 原版 tracking 首包的唯一入口：缓存存在时由 ShadowPull 取代 FULL，否则保留原版包建立基线。
     *  pull FULL 回放（{@link ClientChunkHandler#applyShadowPullFull}）必须放行：
     *  否则会把刚收到的权威包当成原版首包再打一次空基线 Pull，落地被标成 SERVER_PUSH。 */
    public static boolean handleNativeChunk(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet) {
        if (packet != null && ClientChunkHandler.isPendingPullApply(packet.getX(), packet.getZ())) {
            nativeBypassPendingPull.incrementAndGet();
            return false;
        }
        if (ClientChunkPipeline.getInstance().isApplyInProgress()) {
            nativeBypassApplyInProgress.incrementAndGet();
            return false;
        }
        if (packet == null || !io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientCacheEnabled()
                || !ShadowLightCompute.isEnabled()) {
            nativeBypassEngineOff.incrementAndGet();
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            nativeBypassEngineOff.incrementAndGet();
            return false;
        }
        String dimension = LevelCompat.getDimensionId(minecraft.level);
        ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
        if (dimension == null) {
            nativeBypassEngineOff.incrementAndGet();
            return false;
        }
        boolean intercepted = tryInterceptForCompare(dimension, pos,
                () -> io.github.limuqy.mc.hassium.shadow.light.ShadowVanillaLightPipeline.submitVisible(
                        dimension, pos, packet,
                        io.github.limuqy.mc.hassium.platform.client.TraceOrigin.SERVER_PUSH));
        if (intercepted) {
            nativeIntercepted.incrementAndGet();
        } else {
            nativeBypassEngineOff.incrementAndGet();
        }
        return intercepted;
    }

    /**
     * 原生整柱包（vanilla chunk packet）在 {@link #handleNativeChunk} 的分流计数。
     * <p>
     * 用途：这些包一旦旁路，就绕过 Compare+Pull **直接落地** → 表现为「区块加载上升、缓存命中下降」，
     * 且 {@code cacheMiss}/{@code cacheStale} 保持 0（因为它们压根没进比较）。2026-09-20 实测：
     * 1.21.1 R2 有 408 柱未命中缓存，而 cacheMiss/cacheStale 全 0 ⇒ 必须用这组计数确认旁路占比。
     */
    private static final java.util.concurrent.atomic.AtomicLong nativeBypassPendingPull =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong nativeBypassApplyInProgress =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong nativeBypassEngineOff =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong nativeIntercepted =
            new java.util.concurrent.atomic.AtomicLong();
    /** 拦截到无基线包后转交 SeedGen 本地生成的柱数（A1-③ 接管；不与 authoritativeRequests 重叠）。 */
    private static final java.util.concurrent.atomic.AtomicLong seedGenIntercepted =
            new java.util.concurrent.atomic.AtomicLong();

    public static long nativeBypassPendingPullCount() {
        return nativeBypassPendingPull.get();
    }

    public static long nativeBypassApplyInProgressCount() {
        return nativeBypassApplyInProgress.get();
    }

    public static long nativeBypassEngineOffCount() {
        return nativeBypassEngineOff.get();
    }

    public static long nativeInterceptedCount() {
        return nativeIntercepted.get();
    }

    public static long seedGenInterceptedCount() {
        return seedGenIntercepted.get();
    }

    /**
     * 统一 Compare+Pull 结局分解（R2 缓存复用归因）。
     * <p>
     * 为什么需要：{@code cacheMiss}/{@code cacheStale} 依赖 {@code REQUEST_MODES} 回查，
     * 而该表在 {@code MAX_TRACKED_REQUESTS} 处被整体 clear → 请求量上千时回查恒 miss
     * → 那两个计数结构性恒 0，不能用作判据。这里在**请求侧**按入口计数（不依赖回查）。
     */
    private static final AtomicLong compareRequests = new AtomicLong();
    private static final AtomicLong authoritativeRequests = new AtomicLong();
    private static final AtomicLong responseUnchanged = new AtomicLong();
    private static final AtomicLong responseFull = new AtomicLong();
    private static final AtomicLong responseDelta = new AtomicLong();

    public static long compareRequestCount() {
        return compareRequests.get();
    }

    public static long authoritativeRequestCount() {
        return authoritativeRequests.get();
    }

    public static long responseUnchangedCount() {
        return responseUnchanged.get();
    }

    public static long responseFullCount() {
        return responseFull.get();
    }

    public static long responseDeltaCount() {
        return responseDelta.get();
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

    /** 冷却后重试权威 FULL；冷却未到则只释放在途锁（sweep 稍后再发现）。 */
    private static void retryAuthoritativeFullOnce(String dimension, ChunkPos pos) {
        if (pos == null || dimension == null) {
            return;
        }
        notePullFailure(dimension, pos);
        if (!isPullRetryAllowed(dimension, pos)) {
            return;
        }
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z);
        lastFailAtMs.put(key, System.currentTimeMillis());
        requestAuthoritativeFull(dimension, List.of(pos));
    }

    /** Applies compare-and-pull responses for the current client dimension. */
    public static void handleResponse(ShadowPullResponseS2CPacket response) {
        if (response == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String clientDim = minecraft != null && minecraft.level != null
                ? LevelCompat.getDimensionId(minecraft.level) : null;
        if (minecraft == null || minecraft.level == null
                || !response.dimension().equals(clientDim)) {
            io.github.limuqy.mc.hassium.utils.DebugLogger.info(
                    io.github.limuqy.mc.hassium.utils.DebugLogger.LogType.NETWORK,
                    "[SHADOW_PULL] drop response dim={} client={}",
                    response.dimension(), clientDim);
            return;
        }
        Boolean comparedBaseline = REQUEST_MODES.remove(response.requestId());
        for (ShadowPullResponseS2CPacket.Result result : response.results()) {
            switch (result.kind()) {
                case UNCHANGED -> responseUnchanged.incrementAndGet();
                case FULL -> responseFull.incrementAndGet();
                case DELTA -> responseDelta.incrementAndGet();
                default -> {
                }
            }
            ChunkPos pos = new ChunkPos(result.chunkX(), result.chunkZ());
            long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(response.dimension(), pos.x, pos.z);
            PendingCompare pending = PENDING_COMPARE.remove(key);
            // 任何权威裁决到达都释放 session pull 在途，避免 drain 误判 inflight 后无法补发
            io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance()
                    .clearPullInFlight(response.dimension(), pos);
            if (result.kind() == ShadowPullResponseS2CPacket.Kind.FULL) {
                recordFullResult(comparedBaseline);
                byte[] serverPacket = decompressPullFull(result);
                CompareFullDump.write(response.dimension(), pos, serverPacket,
                        io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry
                                .getInstance().get());
                if (pending != null) {
                    io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                            .confirm(response.dimension(), pos);
                    pending.fallback().run();
                } else {
                    io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                            .confirm(response.dimension(), pos);
                    if (!ClientChunkHandler.applyShadowPullFull(serverPacket)) {
                        Constants.LOG.warn("[SHADOW_PULL] Failed to apply FULL ({}, {})", result.chunkX(), result.chunkZ());
                        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                                .mark(response.dimension(), pos);
                        retryAuthoritativeFullOnce(response.dimension(), pos);
                    }
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.DELTA) {
                net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(result.payload()));
                try {
                    io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                            .confirm(response.dimension(), pos);
                    ShadowLightCompute.submitDelta(SectionDeltaS2CPacket.decode(buffer));
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.recordSectionDeltaRequestsSent(1);
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.recordWireBytesReceived(
                            result.payload().length);
                } catch (Throwable t) {
                    Constants.LOG.warn("[SHADOW_PULL] Failed to apply DELTA ({}, {}), retrying FULL",
                            result.chunkX(), result.chunkZ(), t);
                    if (pending != null) {
                        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                                .confirm(response.dimension(), pos);
                        pending.fallback().run();
                    } else {
                        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                                .mark(response.dimension(), pos);
                        retryAuthoritativeFullOnce(response.dimension(), pos);
                    }
                } finally {
                    buffer.release();
                }
            } else if (result.kind() == ShadowPullResponseS2CPacket.Kind.UNCHANGED) {
                // 先 compare 落地，再允许光/交付（禁止「算完光再 compare」）。
                io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                        .confirm(response.dimension(), pos);
                var shadow = io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry
                        .getInstance().get();
                net.minecraft.world.level.chunk.LevelChunk baseline = shadow == null ? null
                        : shadow.injectedChunk(response.dimension(), pos.x, pos.z);
                // 【2026-09-20 同柱重复交付闸（用户拍板）】客户端已持有该柱（落地凭据在）
                // → 服务端已裁决内容未变，客户端手上的就是同一份数据：**只记命中，不回放重交付**。
                // 此前「保鲜 compare」（ShadowTrackingSession.materialize 对权威注入柱再 compare）
                // 每次都触发一次 publishCachedChunk 重交付（实测 1.20.1 R1 1139 柱重复、2080 次
                // 额外注入，origin 全是 SHADOW_MEMORY_CACHE）。UNCHANGED 仍是真命中（服务端未发
                // 整柱载荷），故命中照记；只跳过对客户端的重复注入。
                if (baseline != null
                        && ShadowLightCompute.hasClientApplyEpoch(response.dimension(), pos)) {
                    accountUnchangedHit(response.dimension(), pos, pending != null);
                    releaseProviderInflight(response.dimension(), pos, true);
                    continue;
                }
                boolean published;
                if (baseline != null && !baseline.isLightCorrect()) {
                    // 本地生成/基线尚未算光：confirm 后进一轮光再 pack（不再二次 compare）
                    published = ShadowLightCompute.submitPreLight(
                            io.github.limuqy.mc.hassium.shadow.track.ShadowChunkSource.CACHE_SNAPSHOT,
                            pos, baseline,
                            shadow.level(response.dimension()),
                            io.github.limuqy.mc.hassium.platform.client.TraceOrigin.LOCAL_GENERATION,
                            false);
                } else {
                    published = ShadowLightCompute.publishCachedChunk(response.dimension(), pos);
                }
                releaseProviderInflight(response.dimension(), pos, published);
                if (published) {
                    accountUnchangedHit(response.dimension(), pos, pending != null);
                }
                if (!published) {
                    Constants.LOG.warn("[SHADOW_PULL] Cache baseline unavailable for ({}, {}), retrying FULL",
                            result.chunkX(), result.chunkZ());
                    io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                            .mark(response.dimension(), pos);
                    if (pending != null) {
                        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                                .confirm(response.dimension(), pos);
                        pending.fallback().run();
                    } else {
                        retryAuthoritativeFullOnce(response.dimension(), pos);
                    }
                }
            } else {
                Constants.LOG.warn("[SHADOW_PULL] Request rejected for ({}, {}): {}",
                        result.chunkX(), result.chunkZ(), result.error());
                if (pending != null) {
                    io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                            .clear(response.dimension(), pos);
                    pending.fallback().run();
                }
                // 无 pending 的拒绝：保留 awaiting，禁止 seedGen 盲交付
                notePullFailure(response.dimension(), pos);
                releaseProviderInflight(response.dimension(), pos, false);
            }
        }
    }

    /**
     * 响应处理尾：释放 Provider inflight，使 tracking 泵可再 acquire/交付。
     * 有影子柱 → completeAcquire；无柱 → failAcquire（允许超时/下一拍重发 pull）。
     */
    private static void releaseProviderInflight(String dimension, ChunkPos pos, boolean expectMaterial) {
        try {
            net.minecraft.world.level.chunk.LevelChunk material = null;
            var server = io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry
                    .getInstance().get();
            if (server != null && dimension != null && pos != null) {
                material = server.injectedChunk(dimension, pos.x, pos.z);
            }
            if (material != null) {
                io.github.limuqy.mc.hassium.shadow.track.VanillaAlignedChunkProvider
                        .completeAcquire(dimension, pos, material);
            } else if (!expectMaterial) {
                io.github.limuqy.mc.hassium.shadow.track.VanillaAlignedChunkProvider
                        .failAcquire(dimension, pos);
            } else {
                // FULL/异步 publish 在途：只清 session 在途锁，Provider future 等 inject 完成
                io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance()
                        .clearPullInFlight(dimension, pos);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 主线程 tick：超时未响应的拦截回退为网络数据注入（防区块黑洞）。 */
    public static void expirePending() {
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<Long, PendingCompare> entry : PENDING_COMPARE.entrySet()) {
            if (now - entry.getValue().timestampMs() > COMPARE_TIMEOUT_MS
                    && PENDING_COMPARE.remove(entry.getKey(), entry.getValue())) {
                // 超时回退用的是已收网络数据（真服包），可视为权威来源
                io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clear(
                        entry.getValue().dimension(),
                        new ChunkPos(io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(entry.getKey()),
                                io.github.limuqy.mc.hassium.utils.DimensionKey.chunkZOf(entry.getKey())));
                entry.getValue().fallback().run();
            }
        }
    }

    /**
     * UNCHANGED 命中记账（口径 2026-09-20 用户拍板）：服务端裁决 UNCHANGED（未下发整柱载荷）
     * + 客户端回放成功 ⇒ 计命中。
     * <p>
     * {@code networkReplaced} ⇒ 拦截模式：网络整柱包已在手且被丢弃，额外记
     * {@code cacheHitNetworkReplaced}（流量节省公式据此扣除重叠）。
     * <p>
     * 注意（用户口径）：**不做「少计」类的额外守卫**——本该出现的 compare（本会话网络已付过账的柱）
     * 要在**源头**不发（见 {@code ShadowTrackingSession.onChunkMaterialized} / 泵的
     * {@code authoritativeLocal} 分支），而不是在这里把命中扣掉。
     */
    private static void accountUnchangedHit(String dimension, ChunkPos pos, boolean networkReplaced) {
        ShadowLightCompute.accountCacheFullHit(dimension, pos);
        if (networkReplaced) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheFullHitNetworkReplaced(
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
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
        lastFailAtMs.clear();
        nativeBypassPendingPull.set(0);
        nativeBypassApplyInProgress.set(0);
        nativeBypassEngineOff.set(0);
        nativeIntercepted.set(0);
        seedGenIntercepted.set(0);
        compareRequests.set(0);
        authoritativeRequests.set(0);
        responseUnchanged.set(0);
        responseFull.set(0);
        responseDelta.set(0);
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
    }

    /** 真客户端切维：作废旧维度在途 compare / 失败冷却，避免坐标碰撞串维。 */
    public static void onClientDimensionChanged() {
        PENDING_COMPARE.clear();
        lastFailAtMs.clear();
        REQUEST_MODES.clear();
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
    }
}



