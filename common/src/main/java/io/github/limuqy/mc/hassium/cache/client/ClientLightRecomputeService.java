package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
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
        applyLightEngine(level, chunk, chunkPos);
        // 仅光照缓存开启时回写磁盘；关闭时只重算不存储
        if (HassiumConfigService.getInstance().isLightCacheEnabled()) {
            updateCacheWithLightData(level, chunkPos, cachedNbt);
        }
    }

    /**
     * 从光照引擎提取光照数据，更新缓存（优先使用内存 NBT）。
     */
    private static void updateCacheWithLightData(ClientLevel level, ChunkPos chunkPos,
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

            int minSection = level.getMinSection();
            int maxSection = level.getMaxSection();
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
                    storage.persist(chunkPos, updatedBytes, contentHash, sectionHashes);
                    ClientChunkDirtyTracker.clear(chunkPos);
                    Constants.LOG.debug("Hassium: Updated cache with light data for {}", chunkPos);
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
     * 断连时清空（保留占位，OVERFLOW 已删除，无状态需清空）。
     * <p>
     * 供 {@code ClientLifecycleHelper} 调用，保持调用方不变。
     */
    public static void clear() {
        // 无状态需清空
    }

    private static void applyLightEngine(ClientLevel level, LevelChunk chunk, ChunkPos chunkPos) {
        long startNs = System.nanoTime();
        try {
#if MC_VER < MC_1_21_2
            int bottomSection = level.getMinSection();
            int topSection = level.getMaxSection();
#else
            int bottomSection = level.getMinSectionY();
            int topSection = level.getMaxSectionY() + 1;
#endif

            LevelLightEngine lightEngine = level.getLightEngine();
            lightEngine.setLightEnabled(chunkPos, true);

            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
                lightEngine.updateSectionStatus(sectionPos, section.hasOnlyAir());
                level.setSectionDirtyWithNeighbors(chunkPos.x, sectionY, chunkPos.z);
            }

            // 只重算本块。禁止对邻居 propagateLightSources：邻居若已从缓存注入正确光照，
            // 再 propagate 会先清空再不全量重建 → 「闪一下又灭」（二次进服相邻块互踩）。
            lightEngine.propagateLightSources(chunkPos);
            pullLightFromNeighborEdges(level, chunkPos, bottomSection, topSection);
            Constants.LOG.debug("Hassium: Recomputed light for chunk {}", chunkPos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply light engine for chunk {}", chunkPos, e);
        } finally {
            long elapsedNs = System.nanoTime() - startNs;
            NetworkStats.recordLightRecomputeTime(elapsedNs);
        }
    }

    private static void pullLightFromNeighborEdges(ClientLevel level, ChunkPos chunkPos,
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
                if (neighborSectionDark(sky, block, chunkPos.x + 1, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int z = 0; z < 16; z++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + 16, y, minZ + z);
                        if (sky.getLightValue(neighborPos) > 0 || block.getLightValue(neighborPos) > 0) {
                            ourPos.set(minX + 15, y, minZ + z);
                            lightEngine.checkBlock(ourPos);
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x - 1, chunkPos.z) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x - 1, sectionY, chunkPos.z)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int z = 0; z < 16; z++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX - 1, y, minZ + z);
                        if (sky.getLightValue(neighborPos) > 0 || block.getLightValue(neighborPos) > 0) {
                            ourPos.set(minX, y, minZ + z);
                            lightEngine.checkBlock(ourPos);
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z + 1) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z + 1)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int x = 0; x < 16; x++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + x, y, minZ + 16);
                        if (sky.getLightValue(neighborPos) > 0 || block.getLightValue(neighborPos) > 0) {
                            ourPos.set(minX + x, y, minZ + 15);
                            lightEngine.checkBlock(ourPos);
                        }
                    }
                }
            }
        }
        if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z - 1) != null) {
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                if (neighborSectionDark(sky, block, chunkPos.x, sectionY, chunkPos.z - 1)) {
                    continue;
                }
                int y0 = sectionY << 4;
                int y1 = y0 + 16;
                for (int x = 0; x < 16; x++) {
                    for (int y = y0; y < y1; y++) {
                        neighborPos.set(minX + x, y, minZ - 1);
                        if (sky.getLightValue(neighborPos) > 0 || block.getLightValue(neighborPos) > 0) {
                            ourPos.set(minX + x, y, minZ);
                            lightEngine.checkBlock(ourPos);
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
