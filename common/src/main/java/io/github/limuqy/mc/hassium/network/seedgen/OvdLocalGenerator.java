package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler.TraceOrigin;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * OVD 本地生成：超视渲染（renderOnly）区域缓存 miss 时，用影子端按服务端世界种子
 * 本地生成区块（与服务器地形一致），以 renderOnly 落地并存入本地缓存（写盘链与缓存
 * 读回 renderOnly 一致：卸载/登出 dump 落盘；光照由影子端算光管线统一承担——
 * 生成的包不带光，落地后影子端按 SectionDelta/空光分支投递
 * {@link ShadowLightCompute}）。
 * <p>
 * Gate：客户端功能 gate 开放 && {@code chunk.ovdLocalGeneration} 开启 &&
 * 服务端种子可用（握手已到；无种子关闭生成——服务端未装 MOD / 握手未到不生成，
 * 维持 OVD miss 退避重试）。生成在后台线程（worldgen CPU 密集），落地经
 * MainThreadDispatcher 主线程预算内执行。
 */
public final class OvdLocalGenerator {

    private static final ConcurrentHashMap<Long, ChunkPos> queue = new ConcurrentHashMap<>();
    /**
     * 将 OVD 数据源（注入/磁盘/生成）提交到影子光照管线，待算光后经 drainReady 以
     * renderOnly 落地。相比直连 buildPacket，能获得 LevelLightEngine 收敛光，避免
     * OVD 磁盘区块“有方块但无光”的虚空/黑块。
     */
    public static boolean submitGeneratedRenderOnly(ChunkPos pos,
                                                    net.minecraft.world.level.chunk.LevelChunk chunk,
                                                    net.minecraft.server.level.ServerLevel level,
                                                    TraceOrigin traceOrigin) {
        if (pos == null || chunk == null || !isLoadEnabled()
                || !ViewDistanceExtensionService.getInstance().shouldKeepAsRenderOnly(pos)) {
            return false;
        }
        return ShadowLightCompute.submitGenerated(pos, chunk, level, true, traceOrigin);
    }
    private static final AtomicBoolean drainRunning = new AtomicBoolean(false);

    private OvdLocalGenerator() {}

    /** 影子端可用：只有影子模式可用时才允许 OVD 队列投递（用于读已注入/磁盘缓存）。 */
    public static boolean isLoadEnabled() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientFeatureGateOpen()) {
            return false;
        }
        if (!ShadowLightCompute.isEnabled()) {
            return false;
        }
        if (!ClientChunkPipeline.getInstance().isHassiumHandshakeDone()) {
            return false;
        }
        return !ShadowServerRegistry.getInstance().isFailed();
    }

    /** 是否允许 worldgen 兜底：严格尊重 chunk.ovdLocalGeneration。 */
    public static boolean isEnabled() {
        return isLoadEnabled()
                && HassiumConfigService.getInstance().isOvdLocalGenerationEnabled();
    }

    /**
     * 请求本地生成一个 renderOnly 区块（任意线程；OVD miss 路径调用）。
     * 同柱已在队列/生成中则跳过（去重，防 miss 退避重试风暴）。
     */
    public static void request(ChunkPos pos) {
        if (pos == null || !isLoadEnabled()) {
            return;
        }
        // 防御：只接受当前仍属于 OVD 环带的请求。调用方（loadRenderOnlyChunk /
        // onRenderOnlyMiss）已各自校验，这里再加一道，防止未来新增调用路径把陈旧
        // pos 重新灌入队列造成“generate→apply→Ignoring→miss→request”风暴。
        if (!ViewDistanceExtensionService.getInstance().shouldKeepAsRenderOnly(pos)) {
            DebugLogger.debug(DebugLogger.LogType.ASYNC,
                    "[OVD_GEN] Ignoring stale request ({}, {})", pos.x, pos.z);
            return;
        }
        long key = chunkPosKey(pos);
        if (queue.putIfAbsent(key, pos) != null) {
            return;
        }
        pump();
    }

    /** 触发后台消费（CAS 防并发）。 */
    private static void pump() {
        if (!drainRunning.compareAndSet(false, true)) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            drainRunning.set(false);
            return;
        }
        try {
            executor.submit(OvdLocalGenerator::drainLoop, TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            drainRunning.set(false);
        }
    }

    /** 后台循环：逐个本地生成 + 主线程 renderOnly 落地；队列空退出。 */
    private static void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted() && !queue.isEmpty()) {
                Long key = queue.keys().nextElement(); // 无序取一（距离无关：OVD 环带已按需请求）
                ChunkPos pos = queue.remove(key);
                if (pos == null) {
                    continue;
                }
                generateAndApply(pos);
            }
        } finally {
            drainRunning.set(false);
            if (!queue.isEmpty() && isLoadEnabled()) {
                pump();
            }
        }
    }

    private static void generateAndApply(ChunkPos pos) {
        try {
            // 出队后先检查：玩家可能已经移动，pos 不再属于当前 OVD 环带。
            // 在后台生成前就丢弃，避免为陈旧区块浪费 worldgen/算光/落盘。
            if (Minecraft.getInstance().level == null
                    || !ViewDistanceExtensionService.getInstance().shouldKeepAsRenderOnly(pos)) {
                DebugLogger.debug(DebugLogger.LogType.ASYNC,
                        "[OVD_GEN] Dropping stale queued chunk ({}, {}) before generation", pos.x, pos.z);
                return;
            }
            ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
            if (server == null) {
                queue.clear(); // 影子端不可用（无种子/失败）：本批放弃，miss 重试兜底
                return;
            }
            ServerLevel level = server.overworld();
            // 优先使用本会话已注入的权威数据（服务端 packet 填充，内容与服务器一致）。
            // 只有从未进入过服务器视距、内存表为空且允许本地生成时才走 worldgen 兜底；
            // 本地生成关闭时只读磁盘缓存，避免用生成结果覆盖真实缓存。
            LevelChunk chunk = server.injectedChunk(pos.x, pos.z);
            String dataSource = chunk != null ? "injected" : null;
            if (chunk == null && isEnabled()) {
                chunk = server.generateChunk(pos);
                dataSource = "generate";
            }
            if (chunk == null) {
                // 本地生成关闭或生成超时：尝试磁盘缓存（旧会话或此前 OVD/GEN 落盘）。
                chunk = server.loadFromDisk(pos);
                if (chunk != null) {
                    dataSource = "disk";
                }
            }
            if (chunk == null) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[OVD_GEN] No source for chunk ({}, {}) (localGenerationEnabled={}, injected=null, disk=null)",
                        pos.x, pos.z, isEnabled());
                return; // 无数据可 apply；miss 退避重试会保留到盘上出现数据
            }
            int nonAirSections = 0;
            for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.getSections()) {
                if (!section.hasOnlyAir()) {
                    nonAirSections++;
                }
            }
            int diagTopY = chunk.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 8, 8);
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[OVD_GEN_DIAG] pos=({},{}) dataSource={} sections={} nonAirSections={} topY={} heightmapKeys={}",
                    pos.x, pos.z, dataSource, chunk.getSections().length, nonAirSections, diagTopY,
                    chunk.getHeightmaps().size());
            if ("generate".equals(dataSource) && nonAirSections == 0) {
                // 生成的区块是全空气：先试磁盘缓存（可能是旧会话真实数据），
                // 仍无真实数据才拒绝 apply，避免用坏生成结果覆盖/显示成虚空。
                LevelChunk diskChunk = server.loadFromDisk(pos);
                if (diskChunk != null) {
                    int diskNonAir = 0;
                    for (net.minecraft.world.level.chunk.LevelChunkSection section : diskChunk.getSections()) {
                        if (!section.hasOnlyAir()) {
                            diskNonAir++;
                        }
                    }
                    if (diskNonAir > 0) {
                        chunk = diskChunk;
                        dataSource = "disk";
                        nonAirSections = diskNonAir;
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[OVD_GEN] Generated chunk ({}, {}) was all-air, falling back to disk cache "
                                        + "(nonAirSections={})", pos.x, pos.z, diskNonAir);
                    } else {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[OVD_GEN] Refusing to apply all-air generated chunk ({}, {}) "
                                        + "(nonAirSections=0) — likely worldgen/heightmap drift, keep previous cache instead",
                                pos.x, pos.z);
                        return;
                    }
                } else {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[OVD_GEN] Refusing to apply all-air generated chunk ({}, {}) "
                                    + "(nonAirSections=0, no disk fallback) — likely worldgen/heightmap drift",
                            pos.x, pos.z);
                    return;
                }
            }
            // 统一交给影子光照管线：算光完成后经 drainReady 以 renderOnly 落地。
            // 不再直连 buildPacket——磁盘/注入区块的光在存档/内存但不在 LevelLightEngine，
            // 直连会打出“有方块但无光”的虚空/黑块包（CHUNK_PROBE skyTop=0 证据）。
            TraceOrigin traceOrigin = null;
            if (DebugLogger.isEnabled(DebugLogger.LogType.CHUNK_APPLY)) {
                traceOrigin = switch (dataSource) {
                    case "injected" -> TraceOrigin.SHADOW_MEMORY_CACHE;
                    case "disk" -> TraceOrigin.SHADOW_DISK_CACHE;
                    case "generate" -> TraceOrigin.LOCAL_GENERATION;
                    default -> null;
                };
            }
            if (!submitGeneratedRenderOnly(pos, chunk, level, traceOrigin)) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[OVD_GEN] Failed to submit chunk ({}, {}) to light pipeline (dataSource={})",
                        pos.x, pos.z, dataSource);
                // 清 pending/miss 登记，避免该柱永久卡在 pendingRenderOnly 不再重试。
                ViewDistanceExtensionService.getInstance().onRenderOnlyMiss(pos);
            }
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[OVD_GEN] Generation failed ({}, {}): {}", pos.x, pos.z, t.toString());
        }
    }

    private static long chunkPosKey(ChunkPos pos) {
        return net.minecraft.world.level.ChunkPos.asLong(pos.x, pos.z);
    }

    /** 诊断：队列大小。 */
    public static int pendingCount() {
        return queue.size();
    }
}
