package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
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
 * Gate：客户端功能 gate 开放 && {@code clientCache.ovdLocalGeneration} 开启 &&
 * 服务端种子可用（握手已到；无种子关闭生成——服务端未装 MOD / 握手未到不生成，
 * 维持 OVD miss 退避重试）。生成在后台线程（worldgen CPU 密集），落地经
 * MainThreadDispatcher 主线程预算内执行。
 */
public final class OvdLocalGenerator {

    private static final ConcurrentHashMap<Long, ChunkPos> queue = new ConcurrentHashMap<>();
    private static final AtomicBoolean drainRunning = new AtomicBoolean(false);

    private OvdLocalGenerator() {}

    /** 门控：功能 gate &&（影子模式自动启用，否则配置开 && 有种子）&& 影子端未失败。 */
    public static boolean isEnabled() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientFeatureGateOpen()) {
            return false;
        }
        // 影子模式（客户端零侵入架构）：OVD 数据源 = 影子端（R1 落盘读回 /
        // 本地生成兜底），不再受 ovdLocalGeneration 配置约束。
        if (!ShadowLightCompute.isEnabled()) {
            if (!cfg.isOvdLocalGenerationEnabled()) {
                return false;
            }
            // 无种子关闭生成：服务端未装 MOD / 握手未到时没有世界种子，本地生成地形与服务器不一致
            if (!ClientChunkPipeline.getInstance().isHassiumHandshakeDone()) {
                return false;
            }
        }
        return !ShadowServerRegistry.getInstance().isFailed();
    }

    /**
     * 请求本地生成一个 renderOnly 区块（任意线程；OVD miss 路径调用）。
     * 同柱已在队列/生成中则跳过（去重，防 miss 退避重试风暴）。
     */
    public static void request(ChunkPos pos) {
        if (pos == null || !isEnabled()) {
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
            if (!queue.isEmpty() && isEnabled()) {
                pump();
            }
        }
    }

    private static void generateAndApply(ChunkPos pos) {
        try {
            ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
            if (server == null) {
                queue.clear(); // 影子端不可用（无种子/失败）：本批放弃，miss 重试兜底
                return;
            }
            ServerLevel level = server.overworld();
            long t0 = System.nanoTime();
            LevelChunk chunk = server.generateChunk(pos);
            if (chunk == null) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[OVD_GEN] Generation timed out ({}, {})", pos.x, pos.z);
                return; // 超时丢弃：OVD miss 退避重试会再次触发
            }
            byte[] data = SeedGenChunkCodec.encode(SeedGenChunkCodec.buildPacket(chunk, level),
                    level.registryAccess());
            long genMs = (System.nanoTime() - t0) / 1_000_000L;
            MainThreadDispatcher.execute(() -> {
                if (Minecraft.getInstance().level == null) {
                    return; // 断连：丢弃（缓存由下次进服重建）
                }
                // renderOnly 落地：不请求 BE、不参与模拟；包经 buildPacket 官方算光
                // （level.getLightEngine()）带光推送，客户端无需补算。
                // apply 失败（出视距竞态）→ onRenderOnlyMiss 会再次触发请求。
                if (ClientChunkHandler.applyChunkData(pos.x, pos.z, data, true)) {
                    DebugLogger.info(DebugLogger.LogType.ASYNC,
                            "[OVD_GEN] Applied locally generated chunk ({}, {}) in {}ms",
                            pos.x, pos.z, genMs);
                }
            }, pos, TaskCategory.BEST_EFFORT);
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
