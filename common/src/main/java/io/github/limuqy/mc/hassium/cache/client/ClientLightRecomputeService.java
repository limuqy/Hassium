package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.mixin.LevelLightEngineAccessor;
import io.github.limuqy.mc.hassium.mixin.LightEngineAccessor;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * 客户端光照重算服务（从 Mixin 抽出，避免 Mixin 类上出现 public static）。
 * <p>
 * 合并 apply+光照 pipeline：剥离光照包到达或超视渲染 renderOnly apply 后，
 * 由调用方在主线程同步调 {@link #applyLightEngineNow}，
 * 不再经过 {@code MainThreadDispatcher} 延迟调度，避免跨帧黑块。
 * <p>
 * 限流由 {@code ClientCacheLoadQueue.processQueueUntil} 的时间预算自然约束
 * （apply+光照作为一个整体受预算限制）。
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
        // 并行光照引擎（默认关）：分流到后台全量重算 + 主线程原子提交，本方法立即返回
        if (HassiumConfigService.getInstance().isParallelLightEngineEnabled()) {
            LightComputeService.getInstance().submitRecompute(chunkPos, cachedNbt);
            return;
        }
        applyLightEngine(level, chunk, chunkPos);
        // 仅光照缓存开启时回写磁盘；关闭时只重算不存储
        if (HassiumConfigService.getInstance().isLightCacheEnabled()) {
            updateCacheWithLightData(level, chunkPos, cachedNbt);
        }
    }

    /**
     * 从光照引擎提取光照数据，更新缓存（优先使用内存 NBT）。
     */
    static void updateCacheWithLightData(ClientLevel level, ChunkPos chunkPos,
                                         net.minecraft.nbt.CompoundTag cachedNbt) {
        try {
            ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
            if (storage == null) return;

            net.minecraft.nbt.CompoundTag nbt = cachedNbt;
            if (nbt == null) {
                // fallback：从磁盘读取
                byte[] cachedData = storage.loadAndDecompress(chunkPos);
                if (cachedData == null) return;
                nbt = ChunkDiskCodec.bytesToNbt(cachedData);
                if (nbt == null) return;
            }

            // 刚重算完：始终用引擎态覆盖磁盘（勿因旧 is_light_on=1 提前 return，
            // SectionDelta 曾留下「flag=1 + 残缺 light」时会永久跳过回写）
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
            net.minecraft.world.level.lighting.LayerLightEventListener skyListener =
                    lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
            net.minecraft.world.level.lighting.LayerLightEventListener blockListener =
                    lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);

            int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(level);
            int maxSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMaxSectionExclusive(level);
            net.minecraft.nbt.ListTag sectionsList = CompoundTagCompat.getList(nbt, "sections");

            boolean hasAnyLight = false;
            for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                int idx = sectionY - minSection;
                if (idx >= sectionsList.size()) break;

                net.minecraft.nbt.Tag t = sectionsList.get(idx);
                if (!(t instanceof net.minecraft.nbt.CompoundTag sectionTag)) continue;

                net.minecraft.core.SectionPos sectionPos =
                        net.minecraft.core.SectionPos.of(chunkPos.x, sectionY, chunkPos.z);

                net.minecraft.world.level.chunk.DataLayer skyData = skyListener.getDataLayerData(sectionPos);
                if (skyData != null && !skyData.isEmpty()) {
                    sectionTag.putByteArray("sky_light", skyData.getData().clone());
                    hasAnyLight = true;
                } else {
                    sectionTag.remove("sky_light");
                }

                net.minecraft.world.level.chunk.DataLayer blockData = blockListener.getDataLayerData(sectionPos);
                if (blockData != null && !blockData.isEmpty()) {
                    sectionTag.putByteArray("block_light", blockData.getData().clone());
                    hasAnyLight = true;
                } else {
                    sectionTag.remove("block_light");
                }
            }

            if (hasAnyLight) {
                nbt.putByte("is_light_on", (byte) 1);
                byte[] updatedBytes = ChunkDiskCodec.nbtToBytes(nbt);
                if (updatedBytes != null) {
                    // 保留原 contentHash / sectionHashes，避免 persist(0) 被 MetadataTable 写成 1
                    long contentHash = storage.readChunkHash(chunkPos);
                    if (contentHash == 0L || contentHash == 1L) {
                        // 入库尚未完成或元数据不可信：勿覆盖 hash，保持 dirty 等卸载补丁
                        Constants.LOG.debug("Hassium: Skip light writeback for {} (hash={})",
                                chunkPos, Long.toHexString(contentHash));
                        return;
                    }
                    long[] sectionHashes = storage.readSectionHashes(chunkPos);
                    // 后台化：压缩 + 写盘由 Cache-Saver 单消费者顺序执行（FIFO 保证与 ingest
                    // 任务最终一致：先入队的无光照 ingest 先写、光照任务后写覆盖；后入队的 ingest
                    // 在防护检查时命中写回记录被跳过）；persist 成功后由后台线程登记写回记录并 clear dirty
                    CacheSaveQueue.getInstance().enqueueLightWriteback(chunkPos, updatedBytes, contentHash, sectionHashes);
                    Constants.LOG.debug("Hassium: Enqueued light writeback for {}", chunkPos);
                }
            }
            // 引擎尚无光照可写：保持 dirty，留给卸载光照补丁
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to update cache with light data for {}", chunkPos, e);
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
        updateCacheWithLightData(level, chunkPos, cachedNbt);
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
        // 官方校准链退役：邻居吸收由 LightComputeService 后台传播域承担
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
        int nodes = safeRunLightUpdates(level.getLightEngine());
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
            ensureColumnDataLayers(level, lightEngine, chunkPos, bottomSection, topSection);

            // 邻居也建层：propagateIncreases 会读邻居 section 的 DataLayer。
            // 1.21.10 双端 R2 已复现：邻居无层 → runLightUpdates NPE → residual 留给 LevelRenderer 崩溃。
            ensureNeighborDataLayers(level, lightEngine, chunkPos, bottomSection, topSection);

            // 只重算本块。禁止对邻居 propagateLightSources：邻居若已从缓存注入正确光照，
            // 再 propagate 会先清空再不全量重建 → 「闪一下又灭」（二次进服相邻块互踩）。
            lightEngine.propagateLightSources(chunkPos);
            pullLightFromNeighborEdges(level, chunkPos, bottomSection, topSection);
            safeRunLightUpdates(lightEngine);
            Constants.LOG.debug("Hassium: Recomputed light for chunk {}", chunkPos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply light engine for chunk {}", chunkPos, e);
            try {
                clearLightQueues(level.getLightEngine());
            } catch (Exception ignored) {
                // best-effort
            }
        } finally {
            long elapsedNs = System.nanoTime() - startNs;
            NetworkStats.recordLightRecomputeTime(elapsedNs);
        }
    }

    /** 为本 chunk 全部 section 分配 DataLayer。 */
    static void ensureColumnDataLayers(ClientLevel level, LevelLightEngine lightEngine,
                                       ChunkPos chunkPos, int bottomSection, int topSection) {
        for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
            SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);
            lightEngine.updateSectionStatus(sectionPos, false);
            level.setSectionDirtyWithNeighbors(chunkPos.x, sectionY, chunkPos.z);
        }
    }

    /** 为已加载邻居 chunk 全 section 分配 DataLayer，供边缘传播读层。 */
    static void ensureNeighborDataLayers(ClientLevel level, LevelLightEngine lightEngine,
                                         ChunkPos chunkPos, int bottomSection, int topSection) {
        ensureNeighborColumnIfLoaded(level, lightEngine, chunkPos.x + 1, chunkPos.z, bottomSection, topSection);
        ensureNeighborColumnIfLoaded(level, lightEngine, chunkPos.x - 1, chunkPos.z, bottomSection, topSection);
        ensureNeighborColumnIfLoaded(level, lightEngine, chunkPos.x, chunkPos.z + 1, bottomSection, topSection);
        ensureNeighborColumnIfLoaded(level, lightEngine, chunkPos.x, chunkPos.z - 1, bottomSection, topSection);
    }

    private static void ensureNeighborColumnIfLoaded(ClientLevel level, LevelLightEngine lightEngine,
                                                     int chunkX, int chunkZ,
                                                     int bottomSection, int topSection) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            return;
        }
        for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
            SectionPos sectionPos = SectionPos.of(chunkX, sectionY, chunkZ);
            lightEngine.updateSectionStatus(sectionPos, false);
        }
    }

    /**
     * 同步 drain light deferred 队列；任何失败都清空 residual，
     * 防止下一帧 {@code LevelRenderer.renderLevel} 未捕获 NPE 崩端。
     *
     * @return 官方 runLightUpdates 处理的传播节点数（propagateIncreases + propagateDecreases 计数，
     * 用于量化校准传播工作量；失败返回 0）
     */
    public static int safeRunLightUpdates(LevelLightEngine lightEngine) {
        try {
            return lightEngine.runLightUpdates();
        } catch (Throwable t) {
            Constants.LOG.error("Hassium: runLightUpdates failed; clearing residual light queues", t);
            clearLightQueues(lightEngine);
            return 0;
        }
    }

    /** 清空 sky/block engine 的 deferred 队列（失败兜底，避免渲染线程再崩）。 */
    public static void clearLightQueues(LevelLightEngine lightEngine) {
        if (lightEngine == null) {
            return;
        }
        LevelLightEngineAccessor accessor = (LevelLightEngineAccessor) lightEngine;
        clearEngineQueues(accessor.hassium$getBlockEngine());
        clearEngineQueues(accessor.hassium$getSkyEngine());
    }

    private static void clearEngineQueues(LightEngine<?, ?> engine) {
        if (engine == null) {
            return;
        }
        LightEngineAccessor acc = (LightEngineAccessor) engine;
        LongOpenHashSet nodes = acc.hassium$getBlockNodesToCheck();
        if (nodes != null) {
            nodes.clear();
        }
        LongArrayFIFOQueue decrease = acc.hassium$getDecreaseQueue();
        if (decrease != null) {
            decrease.clear();
        }
        LongArrayFIFOQueue increase = acc.hassium$getIncreaseQueue();
        if (increase != null) {
            increase.clear();
        }
    }

    static void pullLightFromNeighborEdges(ClientLevel level, ChunkPos chunkPos,
                                           int bottomSection, int topSection) {
        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener sky = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener block = lightEngine.getLayerListener(LightLayer.BLOCK);
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos ourPos = new BlockPos.MutableBlockPos();

        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();

        if (level.getChunkSource().getChunkNow(chunkPos.x + 1, chunkPos.z) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x + 1, sectionY, chunkPos.z)
                        && neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int z = 0; z < 16; z++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + 16, y, minZ + z);
                        int skyN = sky.getLightValue(neighborPos);
                        int skyO = sky.getLightValue(ourPos.set(minX + 15, y, minZ + z));
                        if (skyN > skyO + 1 || skyO > skyN + 1) {
                            lightEngine.checkBlock(ourPos);
                            lightEngine.checkBlock(neighborPos);
                        } else {
                            int blockN = block.getLightValue(neighborPos);
                            int blockO = block.getLightValue(ourPos);
                            if (blockN > blockO + 1 || blockO > blockN + 1) {
                                lightEngine.checkBlock(ourPos);
                                lightEngine.checkBlock(neighborPos);
                            }
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x - 1, chunkPos.z) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x - 1, sectionY, chunkPos.z)
                        && neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int z = 0; z < 16; z++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX - 1, y, minZ + z);
                        int skyN = sky.getLightValue(neighborPos);
                        int skyO = sky.getLightValue(ourPos.set(minX, y, minZ + z));
                        if (skyN > skyO + 1 || skyO > skyN + 1) {
                            lightEngine.checkBlock(ourPos);
                            lightEngine.checkBlock(neighborPos);
                        } else {
                            int blockN = block.getLightValue(neighborPos);
                            int blockO = block.getLightValue(ourPos);
                            if (blockN > blockO + 1 || blockO > blockN + 1) {
                                lightEngine.checkBlock(ourPos);
                                lightEngine.checkBlock(neighborPos);
                            }
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z + 1) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z + 1)
                        && neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int x = 0; x < 16; x++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + x, y, minZ + 16);
                        int skyN = sky.getLightValue(neighborPos);
                        int skyO = sky.getLightValue(ourPos.set(minX + x, y, minZ + 15));
                        if (skyN > skyO + 1 || skyO > skyN + 1) {
                            lightEngine.checkBlock(ourPos);
                            lightEngine.checkBlock(neighborPos);
                        } else {
                            int blockN = block.getLightValue(neighborPos);
                            int blockO = block.getLightValue(ourPos);
                            if (blockN > blockO + 1 || blockO > blockN + 1) {
                                lightEngine.checkBlock(ourPos);
                                lightEngine.checkBlock(neighborPos);
                            }
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z - 1) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z - 1)
                        && neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int x = 0; x < 16; x++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + x, y, minZ - 1);
                        int skyN = sky.getLightValue(neighborPos);
                        int skyO = sky.getLightValue(ourPos.set(minX + x, y, minZ));
                        if (skyN > skyO + 1 || skyO > skyN + 1) {
                            lightEngine.checkBlock(ourPos);
                            lightEngine.checkBlock(neighborPos);
                        } else {
                            int blockN = block.getLightValue(neighborPos);
                            int blockO = block.getLightValue(ourPos);
                            if (blockN > blockO + 1 || blockO > blockN + 1) {
                                lightEngine.checkBlock(ourPos);
                                lightEngine.checkBlock(neighborPos);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean neighborSectionDark(LayerLightEventListener sky, LayerLightEventListener block,
                                               int sectionX, int sectionY, int sectionZ) {
        SectionPos sectionPos = SectionPos.of(sectionX, sectionY, sectionZ);
        return isLightLayerEmpty(sky.getDataLayerData(sectionPos))
                && isLightLayerEmpty(block.getDataLayerData(sectionPos));
    }

    private static boolean isLightLayerEmpty(DataLayer layer) {
        return layer == null || layer.isEmpty();
    }
}
