package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;

/**
 * 影子区块投递/回传管线：远程压缩通道解压出的区块数据统一注入影子服务端
 * （{@link ShadowSeedServer}，完整 ServerLevel + 官方光照引擎 + 持久存档），
 * 影子端算光收敛（原版区块生成后算光同款逻辑）后打包官方区块包（带权威光），
 * 回传客户端主线程走官方通道（{@code ClientPacketListener.handleLevelChunkWithLight}）
 * 落地。客户端不参与缓存读写/光照计算——缓存由影子端世界存档承担
 * （type 126 + chunkHash 落盘）。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>投递（{@link #submit} / {@link #submitGenerated}）：任意线程（解压后台 /
 *       主线程 / SeedGen 生成池），pos→数据 REPLACE 覆盖（同柱新数据盖旧）</li>
 *   <li>消费（后台池单循环 CAS）：注入全部 → 等全局收敛（20ms 轮询，5s 上限）→
 *       打包全部回传</li>
 *   <li>客户端主线程：{@link #drainReady}（帧尾，MixinClientTick）官方通道落地</li>
 * </ul>
 * 影子端不可用（未握手 / 创建失败 / 引擎关闭）时不投递——调用方（
 * {@link ClientChunkHandler#handleCompressedChunk}）走既有客户端直连链（apply +
 * 本地缓存），剥光仅在握手声明引擎可用后发生。
 * <p>
 * <b>注入失败 = 影子链路整体失败</b>：直接置 {@code shadowServerFailed}（与握手失败 /
 * 引擎创建失败同级的降级态——关闭缓存/OVD/SeedGen + 游戏内提示），不做逐柱兜底。
 * <p>
 * 断连（{@link #onDisconnect}）清空全部状态；影子服务端经
 * {@link ShadowServerRegistry} 统一关停（含持久存档保存）。
 */
public final class ShadowLightCompute {

    /** 全局收敛等待上限（注入完成 → 传播算完）。 */
    private static final long CONVERGE_TIMEOUT_MS = 5_000L;
    /** 欠光补发监控上限：传播风暴下收敛可能远超 CONVERGE_TIMEOUT，兜底放弃（R2 标脏全量）。 */
    private static final long LIGHT_COMPLETION_WATCHDOG_MS = 30_000L;
    /** 收敛轮询间隔。 */
    private static final long CONVERGE_POLL_MS = 20L;

    /** 投递队列：pos -> packet（REPLACE）。 */
    private static final ConcurrentHashMap<Long, ClientboundLevelChunkWithLightPacket> pending =
            new ConcurrentHashMap<>();
    /** 分段增量队列：pos -> (dimension, DeltaEntry)。REPLACE 语义：服务端每份 delta
     *  都是「当前服务端状态 vs 客户端基线」的完整差异，后到覆盖先到（内容都正确）。 */
    private static final ConcurrentHashMap<Long, DeltaWork> pendingDeltas =
            new ConcurrentHashMap<>();
    /** 本地生成队列：pos -> (chunk, level)（SeedGen worldgen 完成，等光收敛后打包）。 */
    private static final ConcurrentHashMap<Long, GenEntry> generated = new ConcurrentHashMap<>();
    /** 回传队列：pos -> 官方区块包（带权威光；主线程帧尾官方通道消费）。 */
    private static final ConcurrentHashMap<Long, ClientboundLevelChunkWithLightPacket> ready =
            new ConcurrentHashMap<>();

    private static final AtomicBoolean consumeRunning = new AtomicBoolean(false);

    /** miss 已请求集合（会话内防抖：直推与请求并存时不重复请求；断连清空）。 */
    private static final java.util.Set<Long> requestedMisses = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** delta 请求超时（毫秒）：服务端始终回包（entries/skipped 可空），丢包/断连竞态兜底。 */
    private static final long DELTA_REQUEST_TIMEOUT_MS = 8_000L;
    /** 已发出、未收到 delta 响应的请求（pos → 维度 + 截止时间）；超时回退全量。 */
    private static final ConcurrentHashMap<Long, PendingDelta> pendingDeltaRequests =
            new ConcurrentHashMap<>();

    private record PendingDelta(String dimension, long deadlineMs) {}

    private record DeltaWork(String dimension, io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket.DeltaEntry entry) {}

    private ShadowLightCompute() {}

    /** 影子链路可用（引擎开启 && 握手完成 && 影子端未失败）。 */
    public static boolean isEnabled() {
        return HassiumConfigService.getInstance().isHassiumEngineEnabled()
                && ClientChunkPipeline.getInstance().isHassiumHandshakeDone()
                && !ClientChunkPipeline.getInstance().isShadowServerFailed();
    }

    /**
     * 登录初始化：影子端预创建（后台，不卡主线程）。握手未到时等待；
     * 超时放弃（首个投递触发消费循环时再创建）。
     */
    public static void onLogin() {
        if (!HassiumConfigService.getInstance().isHassiumEngineEnabled()) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            return;
        }
        executor.submit(() -> {
            long deadline = System.currentTimeMillis() + 3_000L;
            while (!ClientChunkPipeline.getInstance().isHassiumHandshakeDone()
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    return;
                }
            }
            ShadowServerRegistry.getInstance().getOrCreate();
        }, TaskCategory.BEST_EFFORT);
    }

    /**
     * 影子端 hash 比对（架构语义：服务端 bloom hit 只发 hash——由影子端决定是否需要
     * 推区块数据）。Netty 线程调用（{@code ClientMetadataHandler.handleChunkHashPacket}）；
     * 查盘/比对/请求全部提交后台池（与 consumeLoop 同池，不阻塞 Netty）。
     * <p>
     * 判定顺序（每块）：
     * <ol>
     *   <li>内存已加载（injectedChunks）→ hash 比对（ShadowStorageHashes 表优先，
     *       无表现算）→ 命中 → 直接回传（已加载区块，含收敛光）；</li>
     *   <li>未加载 → 读影子端存档比对（loadFromDisk，存档 hash）→ 命中 →
     *       加载进影子端（injectLoadedChunk，后续直接内存命中）+ 回传；</li>
     *   <li>不中 / 存档无此柱 → 请求数据（requestFullChunks → 服务端
     *       enqueueDataRequest 推送 → 数据到达走 submit/consumeLoop 注入链）。</li>
     * </ol>
     * 影子端创建失败 / 不可用 → 全部请求（降级态客户端直连 apply 兜底，数据必须到）。
     * 未握手（服务端无 MOD）→ 无 hash 包，本方法不触发。
     */
    public static void handleRemoteHashes(String dimension,
                                          List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (!ClientChunkPipeline.getInstance().isHassiumHandshakeDone()) {
            return; // 未握手：原版直发，无 hash 包
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            return;
        }
        try {
            executor.submit(() -> processRemoteHashes(dimension, entries), TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 池已停（断连竞态）：hash 丢弃，数据由直推/请求兜底
        }
    }

    private static void processRemoteHashes(String dimension,
                                            List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> entries) {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        List<ChunkPos> misses = new ArrayList<>();
        List<ChunkPos> deltaCandidates = new ArrayList<>();
        for (io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry entry : entries) {
            ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
            long remoteHash = entry.chunkHash();
            try {
                if (server != null) {
                    // 1) 内存已加载：hash 表优先（注入时已算），无表现算（OVD 生成块）
                    LevelChunk loaded = server.injectedChunk(pos.x, pos.z);
                    if (loaded != null) {
                        if (chunkHashOf(loaded, pos, remoteHash)) {
                            org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                    .info("Shadow hash cache hit ({}, {}), memory push", pos.x, pos.z);
                            pushReady(chunkPosKey(pos), loaded, server.overworld(), true);
                            continue;
                        }
                        // 内存数据过期（hash MISMATCH）且光干净 → 分段增量候选：
                        // 本地 section hashes 上报，服务端只回变更 section。
                        if (deltaEnabled()
                                && !io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos)) {
                            deltaCandidates.add(pos);
                            continue;
                        }
                        misses.add(pos);
                        continue;
                    }
                    // 2) 存档：读盘比对（存档 hash 收敛光），命中 → 加载进影子端 + 回传。
                    //    光标脏（保存时未收敛落盘）→ 不直接打包，落到 miss 请求走注入重算链。
                    LevelChunk fromDisk = server.loadFromDisk(pos);
                    if (fromDisk != null) {
                        if (diskHashMatches(fromDisk, remoteHash)
                                && !io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos)) {
                            server.injectLoadedChunk(pos, fromDisk);
                            org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                    .info("Shadow hash cache hit ({}, {}), disk push", pos.x, pos.z);
                            pushReady(chunkPosKey(pos), fromDisk, server.overworld(), true);
                            continue;
                        }
                        // 存档数据过期（hash MISMATCH）且光干净 → 分段增量候选：
                        // 基线 = 磁盘旧数据，加载进影子端后等 delta 补 section。
                        if (deltaEnabled()
                                && !io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos)) {
                            server.injectLoadedChunk(pos, fromDisk);
                            deltaCandidates.add(pos);
                            continue;
                        }
                        misses.add(pos);
                        continue;
                    }
                }
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_HASH] Compare failed ({}, {})", pos.x, pos.z);
            }
            // 3) 不中 / 影子端不可用：请求数据
            misses.add(pos);
        }
        if (!deltaCandidates.isEmpty()) {
            requestSectionDeltas(dimension, deltaCandidates);
        }
        if (!misses.isEmpty()) {
            List<ChunkPos> toRequest = new ArrayList<>(misses.size());
            for (ChunkPos pos : misses) {
                if (requestedMisses.add(chunkPosKey(pos))) {
                    toRequest.add(pos);
                }
            }
            if (!toRequest.isEmpty()) {
                io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                        .requestFullChunksPublic(dimension, toRequest, false);
            }
        }
    }

    /** 分段增量门控：配置开启 && 影子链路可用。 */
    private static boolean deltaEnabled() {
        return io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isSectionDeltaEnabled()
                && isEnabled();
    }

    /**
     * 上报本地 section hashes 请求分段增量（后台池调用）：影子端本地有旧数据
     * （内存/磁盘）但 contentHash 与远程权威不一致 → 服务端按 section 比对只回
     * 变更 section + heightmaps + BE。登记超时（{@link #tickPendingDeltaTimeouts}）。
     */
    private static void requestSectionDeltas(String dimension, List<ChunkPos> chunks) {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null || chunks.isEmpty()) {
            return;
        }
        List<io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket.Entry> entries = new ArrayList<>(chunks.size());
        long deadline = System.currentTimeMillis() + DELTA_REQUEST_TIMEOUT_MS;
        for (ChunkPos pos : chunks) {
            LevelChunk chunk = server.injectedChunk(pos.x, pos.z);
            if (chunk == null) {
                continue; // 已被移除/竞态：数据由服务端直推兜底
            }
            long[] sectionHashes = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                    .sectionHashesToArray(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .computeSectionHashes(chunk));
            entries.add(new io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket.Entry(
                    pos.x, pos.z, sectionHashes));
            pendingDeltaRequests.put(chunkPosKey(pos), new PendingDelta(dimension, deadline));
        }
        if (entries.isEmpty()) {
            return;
        }
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        boolean sent = false;
        try {
            new io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket(dimension, entries).encode(buf);
            io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.sendSectionHashRequest(buf);
            sent = true;
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordSectionDeltaRequestsSent(entries.size());
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheLoadEligible(
                    entries.size() * io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] Requested {} section-delta chunks (dimension={})", entries.size(), dimension);
        } catch (Throwable t) {
            // 发送失败 → 立即回退全量（登记的请求清掉，避免超时重复回退）
            for (var e : entries) {
                pendingDeltaRequests.remove(chunkPosKey(new net.minecraft.world.level.ChunkPos(e.chunkX(), e.chunkZ())));
            }
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] Request send failed, fallback full ({} chunks)", entries.size());
            io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                    .requestFullChunksPublic(dimension, chunks, true);
        } finally {
            if (!sent && buf != null) {
                buf.release();
            }
        }
    }

    /**
     * 接收分段增量响应（任意线程：Netty / DataPlane 事件循环）：entries 入
     * consumeLoop 应用（applySectionDelta + 清变更 section 光 + 等收敛回传）；
     * skipped 立即回退全量（服务端视距外/退化保护/异常）。
     */
    public static void submitDelta(io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket packet) {
        if (packet == null || !isEnabled()) {
            return;
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW_DELTA] Received {} delta entries (dimension={})", packet.entries().size(), packet.dimension());
        String dimension = packet.dimension();
        int deltaChunks = 0;
        for (var entry : packet.entries()) {
            long key = chunkPosKey(new net.minecraft.world.level.ChunkPos(entry.chunkX(), entry.chunkZ()));
            pendingDeltas.put(key, new DeltaWork(dimension, entry));
            pendingDeltaRequests.remove(key);
            deltaChunks++;
        }
        if (deltaChunks > 0) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordSectionDeltaReceived(
                    deltaChunks, deltaChunks * io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
        }
        if (!packet.skipped().isEmpty()) {
            List<net.minecraft.world.level.ChunkPos> skipped = new ArrayList<>(packet.skipped().size());
            for (var s : packet.skipped()) {
                long key = chunkPosKey(new net.minecraft.world.level.ChunkPos(s.chunkX(), s.chunkZ()));
                pendingDeltaRequests.remove(key);
                skipped.add(new net.minecraft.world.level.ChunkPos(s.chunkX(), s.chunkZ()));
            }
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] {} chunks skipped by server, fallback full", skipped.size());
            io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                    .requestFullChunksPublic(dimension, skipped, true);
        }
        pump();
    }

    /** 主线程帧尾（MixinClientTick）：delta 请求超时回退全量（服务端始终回包，仅丢包兜底）。 */
    public static void tickPendingDeltaTimeouts() {
        if (pendingDeltaRequests.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        java.util.Map<String, List<net.minecraft.world.level.ChunkPos>> timedOut = new java.util.HashMap<>();
        for (var it = pendingDeltaRequests.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now >= e.getValue().deadlineMs()) {
                net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(e.getKey());
                timedOut.computeIfAbsent(e.getValue().dimension(), k -> new ArrayList<>()).add(pos);
                it.remove();
            }
        }
        for (var e : timedOut.entrySet()) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] {} delta requests timed out, fallback full", e.getValue().size());
            io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                    .requestFullChunksPublic(e.getKey(), e.getValue(), true);
        }
    }

    /** 内存区块 hash：ShadowStorageHashes 表优先（注入时已算），无表现算（与 diskHashMatches 同算法）。 */
    private static boolean chunkHashOf(LevelChunk chunk, ChunkPos pos, long remoteHash) {
        Long tableHash = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(pos);
        if (tableHash != null) {
            return tableHash.longValue() == remoteHash;
        }
        return diskHashMatches(chunk, remoteHash);
    }

    /**
     * 投递一个远程区块（任意线程；启用态 gate）。同柱 REPLACE 覆盖旧数据。
     *
     * @param packet 还原的服务端区块包（空光：剥光数据）
     */
    public static void submit(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (pos == null || packet == null || !isEnabled()) {
            return;
        }
        // 全量数据到达 = 该柱不再等 delta 响应（delta 请求超时登记清除）
        pendingDeltaRequests.remove(chunkPosKey(pos));
        pending.put(chunkPosKey(pos), packet);
        pump();
    }

    /**
     * 投递一个本地生成区块（SeedGen worldgen 完成；任意线程）。区块已在影子端，
     * 无需注入——等全局光收敛（原版生成后算光同款传播）后打包官方包回传。
     */
    public static void submitGenerated(ChunkPos pos,
                                       net.minecraft.world.level.chunk.LevelChunk chunk,
                                       net.minecraft.server.level.ServerLevel level) {
        if (pos == null || chunk == null || !isEnabled()) {
            return;
        }
        generated.put(chunkPosKey(pos), new GenEntry(chunk, level));
        pump();
    }

    /** 触发消费循环（CAS 防并发；已失败/未握手时静默）。 */
    private static void pump() {
        if (!consumeRunning.compareAndSet(false, true)) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            consumeRunning.set(false);
            return;
        }
        try {
            executor.submit(ShadowLightCompute::consumeLoop, TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            consumeRunning.set(false); // 池已停（断连竞态），队列由 onDisconnect 清空
        }
    }

    /** 后台消费循环：注入 → 全局收敛 → 打包回传；pending/generated 空退出。 */
    private static void consumeLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
                if (server == null) {
                    // 未握手（无 MOD/握手竞态）或创建失败：registry 已置 shadowServerFailed，
                    // 整体降级（关闭缓存/OVD/SeedGen）；队列由降级 gate 停止，直接退出。
                    pending.clear();
                    pendingDeltas.clear();
                    generated.clear();
                    return;
                }
                List<Map.Entry<Long, ClientboundLevelChunkWithLightPacket>> batch =
                        new ArrayList<>(pending.entrySet());
                List<Map.Entry<Long, GenEntry>> genBatch = new ArrayList<>(generated.entrySet());
                List<Map.Entry<Long, DeltaWork>> deltaBatch = new ArrayList<>(pendingDeltas.entrySet());
                if (batch.isEmpty() && genBatch.isEmpty() && deltaBatch.isEmpty()) {
                    return; // 全部消费完
                }
                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                        .info("consumeLoop batch={} gen={} delta={}", batch.size(), genBatch.size(), deltaBatch.size());
                for (Map.Entry<Long, ClientboundLevelChunkWithLightPacket> e : batch) {
                    ChunkPos pos = new ChunkPos(e.getKey());
                    long remoteHash = io.github.limuqy.mc.hassium.network.ClientChunkPipeline
                            .getInstance().peekPendingContentHash(pos.x, pos.z);
                    // 磁盘缓存优先：R1/R2 重推区块若与影子端存档 contentHash 一致
                    // （远程权威 hash 比对），直接用存档收敛光打包推送，跳过注入/重算/等收敛。
                        LevelChunk fromDisk = server.loadFromDisk(pos);
                        if (fromDisk != null) {
                            org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                    .debug("Shadow disk loaded ({}, {}), remoteHash={}", pos.x, pos.z, remoteHash);
                            // 光标脏（保存时未收敛落盘的欠光数据）不直接打包——落到注入清光重算链
                            if (remoteHash != 0L && diskHashMatches(fromDisk, remoteHash)
                                    && !io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos)) {
                                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                        .info("Shadow disk cache hit ({}, {}), direct push", pos.x, pos.z);
                                try {
                                    io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheLoadEligible(
                                            io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                    io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheFullHit(
                                            io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                    pushReady(e.getKey(), fromDisk,
                                            server.overworld(), true /* converged: 存档即收敛光 */);
                                    pending.remove(e.getKey());
                                    continue;
                                } catch (Throwable t) {
                                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                            "[SHADOW_CHUNK] Disk push failed ({}, {})", pos.x, pos.z);
                                }
                            }
                        }
                    if (!server.injectChunk(pos, e.getValue())) {
                        // 注入失败 = 影子链路整体失败：走与握手失败/创建失败同级的
                        // 关闭核心逻辑（shadowServerFailed → 缓存/OVD/SeedGen 关闭 + 提示）。
                        pending.clear();
                        pendingDeltas.clear();
                        generated.clear();
                        ShadowServerRegistry.getInstance().failShadowServer();
                        return;
                    }
                }
                // 分段增量应用：本地基线 chunk 上就地覆盖变更 section + heightmaps + BE，
                // 变更 section 清光（applySectionDelta 内）→ 与注入共享下方收敛等待。
                java.util.Set<Long> deltaFailed = new java.util.HashSet<>();
                for (Map.Entry<Long, DeltaWork> e : deltaBatch) {
                    long key = e.getKey();
                    if (!pendingDeltas.containsKey(key)) {
                        continue; // REPLACE 后旧条目已被新 batch 接管 / 断连清理
                    }
                    pendingDeltas.remove(key);
                    ChunkPos pos = new ChunkPos(key);
                    DeltaWork work = e.getValue();
                    if (!server.applySectionDelta(pos, work.entry().changedSections(),
                            work.entry().heightmaps(), work.entry().blockEntities())) {
                        // 基线缺失 / 应用失败 → 回退全量（正确性优先）；跳过本 chunk 回传
                        deltaFailed.add(key);
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_DELTA] Apply failed ({}, {}), fallback full", pos.x, pos.z);
                        List<ChunkPos> fallback = new ArrayList<>(1);
                        fallback.add(pos);
                        io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                                .requestFullChunksPublic(work.dimension(), fallback, true);
                    } else {
                        // 成功应用：记增量统计（估算原版全量等价值）
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheDeltaSaved(
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                    }
                }
                // 等全局收敛（注入/生成/delta 后引擎传播——原版区块生成后算光同款逻辑）
                long deadline = System.currentTimeMillis() + CONVERGE_TIMEOUT_MS;
                while (!server.isLightConverged() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(CONVERGE_POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                boolean converged = server.isLightConverged();
                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                        .info("consumeLoop converge: converged={} (batch={} gen={} delta={}, waitWindow={}ms)",
                                converged, batch.size(), genBatch.size(), deltaBatch.size(),
                                CONVERGE_TIMEOUT_MS);
                for (Map.Entry<Long, ClientboundLevelChunkWithLightPacket> e : batch) {
                    long key = e.getKey();
                    if (!pending.containsKey(key)) {
                        continue; // REPLACE 后旧条目已被新 batch 接管 / 断连清理
                    }
                    pending.remove(key);
                    ChunkPos pos = new ChunkPos(key);
                    // 收敛超时也打包直推（数据完整，光欠由后续传播/相邻块补齐）——
                    // 客户端不参与光照计算。
                    try {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        // 注入区块不经 ChunkMap 正规加载，从注入表取用
                        net.minecraft.world.level.chunk.LevelChunk chunk =
                                server.injectedChunk(pos.x, pos.z);
                        if (chunk == null) {
                            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                    "[SHADOW_CHUNK] Chunk missing after inject ({}, {})",
                                    pos.x, pos.z);
                            continue;
                        }
                        pushReady(key, chunk, level, converged);
                    } catch (Throwable t) {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_CHUNK] Build failed ({}, {})", pos.x, pos.z);
                    }
                }
                for (Map.Entry<Long, GenEntry> e : genBatch) {
                    long key = e.getKey();
                    if (!generated.containsKey(key)) {
                        continue; // 断连清理
                    }
                    generated.remove(key);
                    try {
                        pushReady(key, e.getValue().chunk, e.getValue().level, converged);
                    } catch (Throwable t) {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_CHUNK] Generated build failed ({}, {})",
                                new ChunkPos(key).x, new ChunkPos(key).z);
                    }
                }
                // 分段增量回传：apply 后的 chunk 打包（带收敛光；收敛超时 → 欠光 + 标脏，R2 全量兜底）
                for (Map.Entry<Long, DeltaWork> e : deltaBatch) {
                    long key = e.getKey();
                    if (deltaFailed.contains(key) || pendingDeltas.containsKey(key)) {
                        continue; // apply 失败已回退全量 / 已被新 delta REPLACE，下一轮处理
                    }
                    ChunkPos pos = new ChunkPos(key);
                    try {
                        net.minecraft.world.level.chunk.LevelChunk chunk =
                                server.injectedChunk(pos.x, pos.z);
                        if (chunk == null) {
                            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                    "[SHADOW_DELTA] Chunk missing after apply ({}, {})", pos.x, pos.z);
                            continue;
                        }
                        pushReady(key, chunk, server.overworld(), converged);
                    } catch (Throwable t) {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_DELTA] Build failed ({}, {})", pos.x, pos.z);
                    }
                }
            }
        } finally {
            consumeRunning.set(false);
            // 竞态：退出瞬间有新投递 → 重新触发
            if ((!pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty()) && isEnabled()) {
                pump();
            }
        }
    }

    /** 打包官方包（带权威光）入回传队列。 */
    private static void pushReady(long key, net.minecraft.world.level.chunk.LevelChunk chunk,
                                  net.minecraft.server.level.ServerLevel level, boolean converged) {
        ChunkPos pos = chunk.getPos();
        ClientboundLevelChunkWithLightPacket packet = SeedGenChunkCodec.buildPacket(chunk, level);
        if (packet == null) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Build packet failed ({}, {})", pos.x, pos.z);
            return;
        }
        ready.put(key, packet);
        // 运行期光照标脏（用户语义：只有 converge 超时的欠光块标记，R2 读盘命中
        // 不得直接打包，必须重算）：converged=false → 标脏；true → 清除（重算收敛）。
        // 标脏表跨影子端关停保留（进程内），R2 继承 R1 的欠光状态。
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos, !converged);
        if (!converged) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Converge timeout ({}, {}), pushing with partial light",
                    pos.x, pos.z);
            // 欠光补发：引擎传播仍在后台继续；等全局收敛后重新打包（完整光）覆盖旧包，
            // drainReady 下一帧再 apply 一次 → 黑块窗口 = 剩余传播时间，不永久残留。
            pendingLightCompletion.add(key);
            scheduleLightCompletionPush();
        } else {
            pendingLightCompletion.remove(key);
        }
    }

    /** 收敛补发监控（consumeLoop 超时分支登记的欠光块）：轮询引擎收敛 → 重新回传。 */
    private static final java.util.Set<Long> pendingLightCompletion =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean lightCompletionWatcherScheduled;

    private static void scheduleLightCompletionPush() {
        if (lightCompletionWatcherScheduled) {
            return;
        }
        lightCompletionWatcherScheduled = true;
        io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor executor =
                io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor.getClient();
        if (executor == null) {
            lightCompletionWatcherScheduled = false;
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
                    long deadline = System.currentTimeMillis() + LIGHT_COMPLETION_WATCHDOG_MS;
                    while (!pendingLightCompletion.isEmpty() && server != null
                            && System.currentTimeMillis() < deadline) {
                        if (server.isLightConverged()) {
                            break;
                        }
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    for (Long key : pendingLightCompletion) {
                        net.minecraft.world.level.chunk.LevelChunk chunk =
                                server != null ? server.injectedChunk(new ChunkPos(key).x, new ChunkPos(key).z) : null;
                        if (chunk != null) {
                            pushReady(key, chunk, server.overworld(), server.isLightConverged());
                        }
                    }
                    pendingLightCompletion.clear();
                } catch (Throwable t) {
                    pendingLightCompletion.clear();
                } finally {
                    lightCompletionWatcherScheduled = false;
                }
            }, io.github.limuqy.mc.hassium.concurrent.TaskCategory.BEST_EFFORT);
        } catch (Throwable t) {
            lightCompletionWatcherScheduled = false;
        }
    }

    /**
     * 帧尾（MixinClientTick，渲染前）：落地全部就绪回传——官方通道
     * （{@code ClientPacketListener.handleLevelChunkWithLight}）直接主线程调用。
     * 客户端原版 apply 路径，无 Hassium 定制 apply/预算（预算化由
     * MixinVanillaChunkApplyBudget 原样生效）。
     */
    public static void drainReady() {
        if (ready.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientPacketListener connection =
                mc != null ? mc.getConnection() : null;
        if (connection == null) {
            ready.clear(); // 断连竞态：丢弃（重连后由数据包路径重新提交）
            return;
        }
        for (Map.Entry<Long, ClientboundLevelChunkWithLightPacket> e : ready.entrySet()) {
            if (ready.remove(e.getKey(), e.getValue())) {
                try {
                    connection.handleLevelChunkWithLight(e.getValue());
                } catch (Throwable t) {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[SHADOW_CHUNK] Official channel apply failed ({}, {})",
                            new ChunkPos(e.getKey()).x, new ChunkPos(e.getKey()).z);
                }
            }
        }
    }

    /** 断连清理：清空投递/生成/回传（影子服务端由 registry 统一关停保存）。 */
    public static void onDisconnect() {
        pending.clear();
        pendingDeltas.clear();
        pendingDeltaRequests.clear();
        generated.clear();
        ready.clear();
        requestedMisses.clear();
        pendingLightCompletion.clear();
        consumeRunning.set(false);
    }

    /** 磁盘区块 contentHash 与远程权威 hash 比对（同 ChunkContentHashUtil 算法）。 */
    private static boolean diskHashMatches(LevelChunk chunk, long remoteHash) {
        try {
            long diskHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                    .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .computeSectionHashes(chunk));
            if (diskHash != remoteHash) {
                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                        .debug("Shadow disk hash MISMATCH disk={} remote={}", diskHash, remoteHash);
            }
            return diskHash == remoteHash;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long chunkPosKey(ChunkPos pos) {
        // 必须与官方 ChunkPos.asLong(x, z) 编码一致（x 低位、z 高位）：
        // 之前自定义 (x<<32)|z 与官方相反，consumeLoop 用 new ChunkPos(key) 解出 (z,x) 对调，
        // 导致 hash/数据跨坐标互换（consume [x,z] 拿到 [z,x] 的 packet）。
        return net.minecraft.world.level.ChunkPos.asLong(pos.x, pos.z);
    }

    /** 诊断：投递队列大小。 */
    public static int pendingCount() {
        return pending.size();
    }

    /** 诊断：回传队列大小。 */
    public static int readyCount() {
        return ready.size();
    }

    private record GenEntry(net.minecraft.world.level.chunk.LevelChunk chunk,
                            net.minecraft.server.level.ServerLevel level) {}
}
