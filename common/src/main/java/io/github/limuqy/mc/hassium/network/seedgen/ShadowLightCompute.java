package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.cache.client.ClientChunkDirtyTracker;
import io.github.limuqy.mc.hassium.cache.client.HassiumLightHooks;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 影子光照投递/回传管线：启用态下客户端不计算光照，所有区块数据统一投递影子端
 * （完整 ServerLevel + 官方光照引擎），影子端注入 + 传播收敛后回传客户端主线程
 * 轻量落地（{@link HassiumLightHooks#swapDataLayer} 双缓冲原语），并标脏写回缓存
 * （收敛光落盘）。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>投递（{@link #submit}）：任意线程（MixinLightRecompute TAIL 主线程 / 后台 apply），
 *       pos→packet REPLACE 覆盖（同柱新数据盖旧，避免旧数据光照污染）</li>
 *   <li>消费（后台池单循环 CAS）：注入全部 → 等全局收敛（20ms 轮询，5s 上限）→
 *       提取全部回传；注入失败/超时 → 该柱 failed（帧尾单柱兜底重算）</li>
 *   <li>客户端主线程：{@link #applyIfReady}（apply 后立即调）/ {@link #drainReady}
 *       （帧尾，MixinClientTick）+ {@link #isFailed}（单柱兜底查询）</li>
 * </ul>
 * 断连（{@link #onDisconnect}）清空全部状态；影子服务端经
 * {@link ShadowServerRegistry} 共享（SeedGen 与光照同一 ServerLevel）。
 */
public final class ShadowLightCompute {

    /** 全局收敛等待上限（注入完成 → 传播算完）。 */
    private static final long CONVERGE_TIMEOUT_MS = 5_000L;
    /** 收敛轮询间隔。 */
    private static final long CONVERGE_POLL_MS = 20L;

    /** 投递队列：pos -> packet（REPLACE）。 */
    private static final ConcurrentHashMap<Long, ClientboundLevelChunkWithLightPacket> pending =
            new ConcurrentHashMap<>();
    /** 回传队列：pos -> light patch（主线程消费）。 */
    private static final ConcurrentHashMap<Long, ShadowLightPatch> ready =
            new ConcurrentHashMap<>();
    /** 单柱失败标记（注入失败/收敛超时；帧尾据此走客户端兜底重算）。 */
    private static final ConcurrentHashMap<Long, Boolean> failed =
            new ConcurrentHashMap<>();

    private static final AtomicBoolean consumeRunning = new AtomicBoolean(false);

    private ShadowLightCompute() {}

    private static boolean isEnabled() {
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
     * 投递一个区块包（任意线程；启用态 gate）。同柱 REPLACE 覆盖旧包。
     */
    public static void submit(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (pos == null || packet == null || !isEnabled()) {
            return;
        }
        pending.put(chunkPosKey(pos), packet);
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

    /** 后台消费循环：注入 → 全局收敛 → 提取回传；pending 空退出。 */
    private static void consumeLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
                if (server == null) {
                    // 未握手（无 MOD/握手竞态）或创建失败：全部 failed → 帧尾兜底重算
                    failAllPending();
                    return;
                }
                List<Map.Entry<Long, ClientboundLevelChunkWithLightPacket>> batch =
                        new ArrayList<>(pending.entrySet());
                if (batch.isEmpty()) {
                    return; // 全部消费完
                }
                boolean ok = true;
                for (Map.Entry<Long, ClientboundLevelChunkWithLightPacket> e : batch) {
                    long key = e.getKey();
                    ChunkPos pos = new ChunkPos(e.getKey());
                    if (!server.injectChunk(pos, e.getValue())) {
                        pending.remove(key);
                        failed.put(key, Boolean.TRUE);
                        ok = false;
                    }
                }
                if (!ok && pending.isEmpty()) {
                    return;
                }
                // 等全局收敛（注入后引擎传播）
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
                int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat
                        .getMinSection(server.overworld());
                int maxSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat
                        .getMaxSectionExclusive(server.overworld());
                for (Map.Entry<Long, ClientboundLevelChunkWithLightPacket> e : batch) {
                    long key = e.getKey();
                    if (!pending.containsKey(key)) {
                        continue; // REPLACE 后旧条目已被新 batch 接管 / 断连清理
                    }
                    ChunkPos pos = new ChunkPos(key);
                    pending.remove(key);
                    if (!converged) {
                        failed.put(key, Boolean.TRUE);
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_LIGHT] Converge timeout ({}, {}) -> client recompute fallback",
                                pos.x, pos.z);
                        continue;
                    }
                    try {
                        ShadowLightPatch patch = server.extractLight(pos, minSection, maxSection);
                        ready.put(key, patch);
                        DebugLogger.info(DebugLogger.LogType.ASYNC,
                                "[SHADOW_LIGHT] Light ready ({}, {})", pos.x, pos.z);
                    } catch (Throwable t) {
                        failed.put(key, Boolean.TRUE);
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_LIGHT] Extract failed ({}, {}) -> client recompute fallback", pos.x, pos.z);
                    }
                }
            }
        } finally {
            consumeRunning.set(false);
            // 竞态：退出瞬间有新投递 → 重新触发
            if (!pending.isEmpty() && isEnabled()) {
                pump();
            }
        }
    }

    /** 创建失败/未握手：当前 pending 全部 failed（帧尾兜底重算）。 */
    private static void failAllPending() {
        for (Long key : pending.keySet()) {
            failed.put(key, Boolean.TRUE);
        }
        pending.clear();
    }

    /**
     * 客户端主线程：区块 apply 后立即调用。回传就绪 → 落地光照 + 清失败标记。
     */
    public static boolean applyIfReady(ChunkPos pos) {
        long key = chunkPosKey(pos);
        ShadowLightPatch patch = ready.remove(key);
        if (patch == null) {
            return false;
        }
        failed.remove(key);
        applyPatch(patch);
        return true;
    }

    /** 帧尾（MixinClientTick，渲染前）：落地全部就绪回传。 */
    public static void drainReady() {
        for (Map.Entry<Long, ShadowLightPatch> e : ready.entrySet()) {
            if (ready.remove(e.getKey(), e.getValue())) {
                applyPatch(e.getValue());
            }
        }
    }

    /**
     * 帧尾单柱兜底（MixinClientTick，drainReady 后）：影子端注入失败/收敛超时的柱
     * 直接放弃——客户端无本地光照逻辑；引擎创建失败时整体降级（缓存/OVD/SeedGen 关闭），
     * 剥光已在握手侧协商（未声明引擎可用 → 服务端不剥），黑块不因单柱失败扩大。
     */
    public static void drainFailedRecompute() {
        failed.clear();
    }

    /** 单柱失败标记（帧尾兜底重算查询）。 */
    public static boolean isFailed(ChunkPos pos) {
        return failed.containsKey(chunkPosKey(pos));
    }

    /** 清失败标记（兜底重算执行后）。 */
    public static void clearFailed(ChunkPos pos) {
        failed.remove(chunkPosKey(pos));
    }

    /**
     * 主线程落地：逐 section swapDataLayer（双缓冲原语）+ 标脏（收敛光写盘）。
     * null 槽位（空 section）跳过，保持客户端原样。
     */
    private static void applyPatch(ShadowLightPatch patch) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc != null ? mc.level : null;
        if (level == null) {
            return; // 断连竞态：回传丢弃（重连后由数据包路径重新提交）
        }
        long t0 = System.nanoTime();
        LevelLightEngine lightEngine = level.getLightEngine();
        for (int i = 0; i < patch.sky().length; i++) {
            SectionPos sp = SectionPos.of(patch.pos(), patch.bottomSection() + i);
            if (patch.sky()[i] != null) {
                HassiumLightHooks.INSTANCE.swapDataLayer(lightEngine, LightLayer.SKY, sp, patch.sky()[i]);
            }
            if (patch.block()[i] != null) {
                HassiumLightHooks.INSTANCE.swapDataLayer(lightEngine, LightLayer.BLOCK, sp, patch.block()[i]);
            }
            level.setSectionDirtyWithNeighbors(patch.pos().x, patch.bottomSection() + i, patch.pos().z);
        }
        ClientChunkDirtyTracker.markDirty(patch.pos());
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW_LIGHT] Applied light patch ({}, {}) in {}ms",
                patch.pos().x, patch.pos().z, ms);
    }

    /** 断连清理：清空投递/回传/失败标记（影子服务端由 registry 统一关停）。 */
    public static void onDisconnect() {
        pending.clear();
        ready.clear();
        failed.clear();
        consumeRunning.set(false);
    }

    private static long chunkPosKey(ChunkPos pos) {
        return ((long) pos.x << 32) | (pos.z & 0xFFFFFFFFL);
    }

    /** 诊断：投递队列大小。 */
    public static int pendingCount() {
        return pending.size();
    }

    /** 诊断：回传队列大小。 */
    public static int readyCount() {
        return ready.size();
    }

    /** 诊断：失败计数。 */
    public static int failedCount() {
        return failed.size();
    }
}
