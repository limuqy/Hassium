package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.concurrent.ExecutorFactory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import io.github.limuqy.mc.hassium.network.SeedRefS2CPacket;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * SeedGen 生成执行器（Phase 2）：接收 SeedRef → 影子服务端本地生成 FULL 区块
 * → 按直推同格式压缩 → 喂给 {@link ClientChunkHandler#handleCompressedChunk}（复用解压/应用链）。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>网络线程入队（{@link #enqueue}，非阻塞）</li>
 *   <li>平台线程池（seedGenThreads，CPU 密集：worldgen + 编码 + 压缩，避免虚拟线程超订）</li>
 *   <li>单个 drain 循环互斥运行（AtomicBoolean CAS），串行生成（影子服务端单实例）</li>
 * </ul>
 * 失败/超时统一回退全量请求（{@link ClientMetadataHandler#fallbackToFullRequest}），正确性优先。
 */
public final class SeedGenExecutor {

    private static final SeedGenExecutor INSTANCE = new SeedGenExecutor();

    private final SeedGenQueue queue = new SeedGenQueue();
    private final AtomicBoolean drainRunning = new AtomicBoolean(false);
    private volatile ExecutorService pool;

    private SeedGenExecutor() {}

    public static SeedGenExecutor getInstance() {
        return INSTANCE;
    }

    /**
     * 处理一个 SeedRef。返回 true = 已接管（入队，将本地生成）；
     * false = 未接管（门控未过/配置关闭），调用方应回退全量请求。
     */
    public boolean handleSeedRef(SeedRefS2CPacket packet) {
        if (!isEnabled()) {
            return false;
        }
        queue.enqueue(new ChunkPos(packet.chunkX(), packet.chunkZ()),
                packet.contentHash(), packet.sectionHashes());
        DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Queued ({}, {}) hash={} (queue={})",
                packet.chunkX(), packet.chunkZ(), Long.toHexString(packet.contentHash()), queue.size());
        pump();
        return true;
    }

    /** 门控：客户端配置开启 && 服务端 SeedGen 启用 && 影子端未失败。 */
    private boolean isEnabled() {
        if (ShadowServerRegistry.getInstance().isFailed()) {
            return false;
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientSeedGenEnabled() || cfg.getSeedGenThreads() <= 0) {
            return false;
        }
        return ClientChunkPipeline.getInstance().isServerSeedGenEnabled();
    }

    /** 断连清理：停池、关影子服务端（registry 共享）、清队列。 */
    public void onDisconnect() {
        queue.clear();
        ExecutorService p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
        ShadowServerRegistry.getInstance().shutdown();
    }

    /** 触发一次 drain（CAS 防并发；池未建则先建）。 */
    private void pump() {
        ExecutorService p = pool;
        if (p == null || p.isShutdown()) {
            synchronized (this) {
                p = pool;
                if (p == null || p.isShutdown()) {
                    HassiumConfigService cfg = HassiumConfigService.getInstance();
                    p = ExecutorFactory.createPlatform("hassium-seedgen",
                            Math.max(1, cfg.getSeedGenThreads()));
                    pool = p;
                }
            }
        }
        if (drainRunning.compareAndSet(false, true)) {
            try {
                p.submit(this::drain);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                drainRunning.set(false); // 池已停（断连竞态），丢弃本次
            }
        }
    }

    /** 工作循环：取最近未超时条目 → 生成 → 编码 → 压缩 → 交给客户端链；空则退出。 */
    private void drain() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // 超时条目先回退
                for (SeedGenQueue.Entry expired : queue.expire()) {
                    fallback(expired.pos());
                }
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) {
                    break; // 断连/未进服：剩余条目由 onDisconnect 清空
                }
                SeedGenQueue.Entry entry = queue.peekNearest(
                        mc.player.chunkPosition().x, mc.player.chunkPosition().z);
                if (entry == null) {
                    break;
                }
                generateOne(entry);
            }
        } finally {
            drainRunning.set(false);
            // 竞态窗口：drain 退出瞬间有新条目 → 重新触发
            if (!queue.isEmpty() && !ShadowServerRegistry.getInstance().isFailed()) {
                pump();
            }
        }
    }

    private void generateOne(SeedGenQueue.Entry entry) {
        ChunkPos pos = entry.pos();
        try {
            ShadowSeedServer server = shadowServer();
            if (server == null) {
                fallback(pos);
                return;
            }
            long t0 = System.nanoTime();
            LevelChunk chunk = server.generateChunk(pos);
            if (chunk == null) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Generation timeout/failed ({}, {}) -> fallback", pos.x, pos.z);
                fallback(pos);
                return;
            }
            // 生成后 chunkHash 校验：与服务端 SeedRef 下发 hash 比对（同 ChunkContentHashUtil
            // 算法，服务端 packet 路径与客户端内存路径等价性有保证）；不匹配 = 本地 worldgen
            // 与服务器不一致（自定义 datapack 缺失等）→ 走现有回退路径全量拉取，不产出错误地形。
            final long localHash;
            try {
                localHash = ChunkContentHashUtil.combineSectionHashes(
                        ChunkContentHashUtil.computeSectionHashes(chunk));
            } catch (Throwable hashError) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Hash compute failed ({}, {}) -> fallback", pos.x, pos.z);
                fallback(pos);
                return;
            }
            if (localHash != entry.contentHash()) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Hash mismatch ({}, {}): local={} server={} -> fallback full",
                        pos.x, pos.z, Long.toHexString(localHash), Long.toHexString(entry.contentHash()));
                fallback(pos);
                return;
            }
            ServerLevel level = server.overworld();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Generated ({}, {}) in {}ms",
                    pos.x, pos.z, ms);
            NetworkStats.recordLocallyGeneratedChunk(NetworkStats.ESTIMATED_CHUNK_BYTES);
            // 统一影子通道：等光收敛（原版生成后算光同款逻辑）→ 打包官方包 →
            // 官方通道落地（客户端不参与缓存/光照）。
            ShadowLightCompute.submitGenerated(pos, chunk, level);
            queue.remove(pos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen generation failed for {}", pos, e);
            fallback(pos);
        }
    }

    /** 影子服务端懒创建（共享 registry；创建失败 → failed + 回退本次）。 */
    private ShadowSeedServer shadowServer() {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN] Shadow server unavailable (no seed / creation failed) -> fallback");
        }
        return server;
    }

    /** 回退全量请求（安全：任何线程可调）。 */
    private void fallback(ChunkPos pos) {
        queue.remove(pos);
        ClientMetadataHandler.fallbackToFullRequestByPos(pos);
    }

    /** 队列内待生成条目数（诊断/测试）。 */
    public int pendingCount() {
        return queue.size();
    }
}
