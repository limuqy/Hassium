package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.promethium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.promethium.light.ParallelLightEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 客户端光照重算服务（从 Mixin 抽出，避免 Mixin 类上出现 public static）。
 * <p>
 * 合并 apply+光照 pipeline：剥离光照包到达或超视渲染 renderOnly apply 后，
 * 由调用方在主线程同步调 {@link #applyLightEngineNow}，
 * 不再经过 {@code MainThreadDispatcher} 延迟调度，避免跨帧黑块。
 * <p>
 * 限流由 {@code ClientCacheLoadQueue.processQueueUntil} 的时间预算自然约束
 * （apply+光照作为一个整体受预算限制）。
 * <p>
 * 官方引擎原语（safeRunLightUpdates / 建层 / 邻居拉光 / 缓存写回）已迁往
 * {@link HassiumLightHooks}（Promethium 引擎经 {@code LightEngineHooks} 消费）；
 * 本类只保留编排逻辑与同步路径。
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
        // 读盘一次：重算用缓存 NBT（先灌旧光，再供并行引擎域推断/壳光/写回复用）。
        // 磁盘 hash 有效才读（R1 空缓存归零），返回 null = 无缓存/不可信。
        net.minecraft.nbt.CompoundTag nbt = cachedNbt;
        if (nbt == null) {
            nbt = loadCachedNbtForRecompute(chunkPos);
        }
        // 先用磁盘缓存光填充引擎（缓存有光时）：内容虽过期，但重算完成前渲染不再黑块。
        // 并行引擎后台提交期间旧光持续可见，提交后原子换成新光。
        restoreCachedLightToEngine(level, chunkPos, nbt);
        // 并行光照引擎（默认关）：分流到后台全量重算 + 主线程原子提交，本方法立即返回
        if (HassiumConfigService.getInstance().isParallelLightEngineEnabled()) {
            ParallelLightEngine.getInstance().submitRecompute(chunkPos, nbt);
            return;
        }
        applyLightEngine(level, chunk, chunkPos);
        // 仅光照缓存开启时回写磁盘；关闭时只重算不存储
        if (HassiumConfigService.getInstance().isLightCacheEnabled()) {
            HassiumLightHooks.INSTANCE.updateCacheWithLightData(level, chunkPos, nbt);
        }
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
     * 重算前把磁盘缓存的旧光照灌进光照引擎（queueSectionData 同步生效，渲染立即可见）。
     * <p>
     * 适用场景：R2 重连 MISMATCH/全量路径拿到无光数据，磁盘缓存仍有旧内容的光
     * （is_light_on=1）。重算（尤其并行引擎异步提交）完成前引擎无光 → 渲染黑块；
     * 先灌旧光可让画面立即亮起，重算完成后原子覆盖为新光。
     * <p>
     * 无缓存 / 缓存无光（is_light_on=0）/ 解析失败 → 静默跳过，行为与改造前一致。
     * 不记 cache hit 统计：内容已过期，重算照常发生。
     * <p>
     * 读盘由 {@link #applyLightEngineNow} 的 {@code loadCachedNbtForRecompute} 统一完成
     * （hash gate 在彼处）；本方法只消费已解析 NBT（null = 无缓存/不可信，跳过）。
     */
    private static void restoreCachedLightToEngine(ClientLevel level, ChunkPos chunkPos,
                                                   net.minecraft.nbt.CompoundTag cachedNbt) {
        try {
            if (cachedNbt == null) {
                return;
            }
            // 不 gate is_light_on：SectionDelta merge 后写盘 is_light_on=0，但未变 section
            // 的旧光字段保留——delta 块重算（并行引擎异步完成）前灌旧光显示，消除
            // 「新地形 + 引擎无光」的黑块窗口，重算完成后原子覆盖。
            // 剥光缓存（is_light_on=0 且全 section 无光字段）循环后 anyRestored=false 自然跳过。
            LevelLightEngine lightEngine = level.getLightEngine();
            int minSection = io.github.limuqy.mc.promethium.compat.LevelHeightCompat.getMinSection(level);
            int maxSection = io.github.limuqy.mc.promethium.compat.LevelHeightCompat.getMaxSectionExclusive(level);
            net.minecraft.nbt.ListTag sectionsList = CompoundTagCompat.getList(cachedNbt, "sections");

            boolean anyRestored = false;
            for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                int idx = sectionY - minSection;
                if (idx >= sectionsList.size()) {
                    break;
                }
                net.minecraft.nbt.Tag t = sectionsList.get(idx);
                if (!(t instanceof net.minecraft.nbt.CompoundTag sectionTag)) {
                    continue;
                }
                SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);

                net.minecraft.nbt.Tag skyTag = sectionTag.get("sky_light");
                if (skyTag instanceof net.minecraft.nbt.ByteArrayTag bat
                        && bat.getAsByteArray().length == DataLayer.SIZE) {
                    DataLayer layer = new DataLayer();
                    System.arraycopy(bat.getAsByteArray(), 0, layer.getData(), 0, DataLayer.SIZE);
                    lightEngine.queueSectionData(LightLayer.SKY, sectionPos, layer);
                    anyRestored = true;
                }

                net.minecraft.nbt.Tag blockTag = sectionTag.get("block_light");
                if (blockTag instanceof net.minecraft.nbt.ByteArrayTag bbat
                        && bbat.getAsByteArray().length == DataLayer.SIZE) {
                    DataLayer layer = new DataLayer();
                    System.arraycopy(bbat.getAsByteArray(), 0, layer.getData(), 0, DataLayer.SIZE);
                    lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, layer);
                    anyRestored = true;
                }
            }
            if (anyRestored) {
                // queueSectionData 直接落 storage 并清空该 section 的传播节点
                // （原版 LightUpdate packet 同路径，渲染立即可见），无需 runLightUpdates；
                // 残留的邻居传播节点由帧尾 flushPendingCalibrations 兜底。
                // 实测在此处 safeRunLightUpdates 会触发旧光向邻居传播，每块数 ms 主线程
                // 开销挤占 apply 预算，R2 加载完成率从 ~100% 掉到 ~22%。
            }
        } catch (Exception e) {
            // 恢复失败不阻断重算：行为退化为改造前（重算完成前短暂无光）
            Constants.LOG.debug("Hassium: Failed to restore cached light for {}", chunkPos, e);
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
     * 断连时清空（自研传播域无跨线程状态需清空；保留占位，断连由 generation 拒绝旧任务）。
     * <p>
     * 供 {@code ClientLifecycleHelper} 调用，保持调用方不变。
     */
    public static void clear() {
        // 无状态需清空（官方校准链已退役，传播域在后台任务内）
    }

    /**
     * 邻居吸收已由自研传播域完成（后台传播域 = 核心柱 ±16 格，覆盖邻居柱全宽；主线程只做
     * memcpy 落地）。本方法为空实现，仅为兼容调用点（{@code MixinLightRecompute}）保留。
     */
    public static void calibrateLoadedNeighbors(ClientLevel level, ChunkPos pos) {
        // 官方校准链退役：邻居吸收由 ParallelLightEngineImpl 后台传播域承担
    }

    /**
     * 帧尾（Minecraft.tick TAIL、渲染前）兜底清空官方光照传播队列：
     * 自研传播域任务不再入官方队列，这里只处理原版方块变化 / 数据包 delta 路径触发的
     * 低频传播（nodes 应接近 0，对比自研铺开前 parsort 轮峰值 32 万）。
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
            int bottomSection = io.github.limuqy.mc.promethium.compat.LevelHeightCompat.getMinSection(level);
            int topSection = io.github.limuqy.mc.promethium.compat.LevelHeightCompat.getMaxSectionExclusive(level);

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
