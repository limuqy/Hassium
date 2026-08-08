package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
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
    private volatile ShadowSeedServer shadowServer;
    /** 影子服务端创建失败后置 true：本会话不再尝试，全部回退。 */
    private volatile boolean disabled;

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

    /** 门控：客户端配置开启 && 服务端 SeedGen 启用。 */
    private boolean isEnabled() {
        if (disabled) {
            return false;
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientSeedGenEnabled() || cfg.getSeedGenThreads() <= 0) {
            return false;
        }
        return ClientChunkPipeline.getInstance().isServerSeedGenEnabled();
    }

    /** 断连清理：停池、关影子服务端、清队列。 */
    public void onDisconnect() {
        queue.clear();
        ExecutorService p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
        ShadowSeedServer server = shadowServer;
        shadowServer = null;
        disabled = false; // 重连后允许重建
        if (server != null) {
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Shutting down shadow seed server");
            server.stopMainLoop();
            SeedGenLevelCompat.shutdown(server);
        }
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
            if (!queue.isEmpty() && !disabled) {
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
            ServerLevel level = server.overworld();
            ClientboundLevelChunkWithLightPacket packet = SeedGenChunkCodec.buildPacket(chunk, level);
            byte[] encoded = packet == null ? null : SeedGenChunkCodec.encode(packet, server.registryAccess());
            byte[] wire = encoded == null ? null : SeedGenChunkCodec.compress(encoded, pos.x, pos.z);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (wire == null) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC, "[SEEDGEN] Encode/compress failed ({}, {}) -> fallback", pos.x, pos.z);
                fallback(pos);
                return;
            }
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Generated ({}, {}) in {}ms, wire={} bytes",
                    pos.x, pos.z, ms, wire.length);
            NetworkStats.recordLocallyGeneratedChunk(NetworkStats.ESTIMATED_CHUNK_BYTES);
            ClientChunkHandler.handleCompressedChunk(wire);
            queue.remove(pos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen generation failed for {}", pos, e);
            fallback(pos);
        }
    }

    /** 影子服务端懒创建（首个生成任务所在线程；创建失败 → disabled + 回退本次）。 */
    private ShadowSeedServer shadowServer() {
        ShadowSeedServer server = shadowServer;
        if (server != null) {
            return server;
        }
        synchronized (this) {
            server = shadowServer;
            if (server != null) {
                return server;
            }
            if (disabled) {
                return null;
            }
            long seed = ClientChunkPipeline.getInstance().getServerSeed();
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Creating shadow seed server (seed={})", seed);
            try {
                server = SeedGenLevelCompat.createShadowServer(seed);
                shadowServer = server;
                return server;
            } catch (Exception e) {
                Constants.LOG.error("Hassium: Shadow seed server creation failed, SeedGen disabled for this session", e);
                disabled = true;
                return null;
            }
        }
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
