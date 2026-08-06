package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 客户端光照重算编排（从 Mixin 抽出，避免 Mixin 类上出现 public static）。
 * <p>
 * 剥离光照包到达或超视渲染 renderOnly apply 后，调用方经 {@link #applyLightEngineNow}
 * 分派重算：
 * <ul>
 *   <li>并行引擎（默认关）：转交 {@code ParallelLightEngine.submitRecompute}，后台 BFS
 *       重算 + 主线程帧尾预算落地（本方法立即返回）。</li>
 *   <li>官方引擎（默认）：入 {@link ClientLightBufferQueue}，帧尾预算内消费
 *       （每帧部分预算，不阻塞 apply 链路）。</li>
 * </ul>
 * 官方引擎原语（safeRunLightUpdates / 建层 / 邻居拉光 / 缓存写回）已迁往
 * {@link HassiumLightHooks}（Promethium 引擎经 {@code LightEngineHooks} 消费）；
 * 本类只保留编排逻辑与两路径共用的同步重算实现（{@link #applyLightEngine}）。
 */
public final class ClientLightRecomputeService {

    private ClientLightRecomputeService() {}

    /**
     * 同步执行光照重算（主线程调用）。
     * <p>
     * 合并 apply+光照 pipeline：{@code applyToLevelFromByteBuf} 后立即调用，
     * 不再经过 {@code MainThreadDispatcher} 延迟调度，避免跨帧黑块。
     * 限流由 {@code ClientCacheLoadQueue.processQueueUntil} 的时间预算自然约束。
     *
     * @param chunkPos 区块坐标
     */
    public static void applyLightEngineNow(ChunkPos chunkPos) {
        applyLightEngineNow(chunkPos, null);
    }

    /**
     * 同步执行光照重算，使用内存中的 NBT（避免从磁盘读取）。
     *
     * @param chunkPos  区块坐标
     * @param cachedNbt 内存中的缓存 NBT（可为 null，null 时回退磁盘读取）
     */
    public static void applyLightEngineNow(ChunkPos chunkPos, net.minecraft.nbt.CompoundTag cachedNbt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return;
        }
        // 加载活跃：续期 JoinBoost 窗口（固定 10s 窗口在 1021 块全量加载下会中途退坡 → 后段掉速）
        ClientMainThreadBudget.noteChunkApplyActivity();
        // 读盘一次：重算用缓存 NBT（供并行引擎域推断/壳光/写回复用）。
        // 磁盘 hash 有效才读（R1 空缓存归零），返回 null = 无缓存/不可信。
        net.minecraft.nbt.CompoundTag nbt = cachedNbt;
        if (nbt == null) {
            nbt = loadCachedNbtForRecompute(chunkPos);
        }
        // 不预灌缓存旧光（restoreCachedLightToEngine 已移除）：磁盘缓存光可能是未收敛/
        // 空光字段（SectionDelta 残缺 light、empty-mask 全 0），灌入引擎显示错误亮度；
        // 重算完成原子落地新光即最终画面，预灌只增加主线程开销与脏显示窗口。
        // 并行光照引擎（默认关；Promethium MOD 缺席自动回退）：分流到后台全量重算 + 主线程
        // 原子提交，本方法立即返回
        if (PromethiumLightBridge.isEnabled()) {
            PromethiumLightBridge.submitRecompute(chunkPos, nbt);
            return;
        }
        // 官方引擎：入统一缓冲队列，帧尾预算内消费（每帧部分预算；缓存写回在消费时完成）。
        // 不在此同步重算——逐块立即重算会挤占 chunk apply 预算造成帧时间锯齿。
        ClientLightBufferQueue.getInstance().enqueue(chunkPos, nbt);
    }

    /**
     * 重算用缓存 NBT：磁盘 hash 有效才读（R1 空缓存归零），返回 null = 无缓存/不可信。
     */
    private static net.minecraft.nbt.CompoundTag loadCachedNbtForRecompute(ChunkPos chunkPos) {
        try {
            ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
            if (storage == null) {
                return null;
            }
            // 轻量前置检查：元数据无有效 hash = 无缓存（或不可信），跳过读盘。
            // R1 缓存为空场景（每块白读盘）与 hash 未写回场景都靠此 gate 归零开销。
            long diskHash = storage.readChunkHash(chunkPos);
            if (diskHash == 0L || diskHash == 1L) {
                return null;
            }
            byte[] cachedData = storage.loadAndDecompress(chunkPos);
            if (cachedData == null) {
                return null;
            }
            return ChunkDiskCodec.bytesToNbt(cachedData);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 公开入口：使用内存 NBT 更新光照缓存（供外部调用方使用）。
     *
     * @param chunkPos  区块坐标
     * @param cachedNbt 内存中的缓存 NBT（可为 null）
     */
    public static void updateCacheWithLightNbt(ChunkPos chunkPos, net.minecraft.nbt.CompoundTag cachedNbt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        HassiumLightHooks.INSTANCE.updateCacheWithLightData(level, chunkPos, cachedNbt);
    }

    /**
     * 帧尾（Minecraft.tick TAIL、渲染前）兜底清空官方光照传播队列：
     * 缓冲队列消费（chunk 重算）已处理自己入队的传播节点；这里只兜底原版方块变化 /
     * 数据包 delta 路径触发的低频传播（nodes 应接近 0，对比自研铺开前 parsort 轮峰值 32 万）。
     */
    public static void flushPendingCalibrations() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long t0 = System.nanoTime();
        int nodes = HassiumLightHooks.INSTANCE.safeRunLightUpdates(level.getLightEngine());
        // 限频诊断：量化官方兜底工作量（每 60 次 flush 打印一次）。
        if (FLUSH_DIAG_COUNT++ % 60 == 0) {
            Constants.LOG.info("[LIGHT-FLUSH] elapsed={}ms nodes={}",
                    (System.nanoTime() - t0) / 1_000_000, nodes);
        }
    }

    /** 帧尾传播诊断限频计数（仅主线程访问）。 */
    private static int FLUSH_DIAG_COUNT = 0;

    static void applyLightEngine(ClientLevel level, LevelChunk chunk, ChunkPos chunkPos) {
        long startNs = System.nanoTime();
        try {
            int bottomSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(level);
            int topSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMaxSectionExclusive(level);

            LevelLightEngine lightEngine = level.getLightEngine();
            lightEngine.setLightEnabled(chunkPos, true);

            // 本块全 section 建 DataLayer（含 hasOnlyAir）。
            // 空气 section 也需要 sky light；且 pullLightFromNeighborEdges 的 checkBlock
            // 会入队边缘空气格。若 updateSectionStatus(true) 则无 DataLayer，
            // 渲染线程 runLightUpdates → getStoredLevel NPE（1.21.3/1.21.5 NeoForge 已复现）。
            HassiumLightHooks.INSTANCE.ensureColumnDataLayers(level, lightEngine, chunkPos, bottomSection, topSection);

            // 邻居也建层：propagateIncreases 会读邻居 section 的 DataLayer。
            // 1.21.10 双端 R2 已复现：邻居无层 → runLightUpdates NPE → residual 留给 LevelRenderer 崩溃。
            HassiumLightHooks.INSTANCE.ensureNeighborDataLayers(level, lightEngine, chunkPos, bottomSection, topSection);

            // 只重算本块。禁止对邻居 propagateLightSources：邻居若已从缓存注入正确光照，
            // 再 propagate 会先清空再不全量重建 → 「闪一下又灭」（二次进服相邻块互踩）。
            lightEngine.propagateLightSources(chunkPos);
            HassiumLightHooks.INSTANCE.pullLightFromNeighborEdges(level, chunkPos, bottomSection, topSection);
            HassiumLightHooks.INSTANCE.safeRunLightUpdates(lightEngine);
            Constants.LOG.debug("Hassium: Recomputed light for chunk {}", chunkPos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply light engine for chunk {}", chunkPos, e);
            try {
                HassiumLightHooks.clearLightQueues(level.getLightEngine());
            } catch (Exception ignored) {
                // best-effort
            }
        } finally {
            long elapsedNs = System.nanoTime() - startNs;
            NetworkStats.recordLightRecomputeTime(elapsedNs);
        }
    }
}
