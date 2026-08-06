package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.mixin.LevelLightEngineAccessor;
import io.github.limuqy.mc.hassium.mixin.LightEngineAccessor;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compat.LevelHeightCompat;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Promethium 光照引擎的 Hassium 侧钩子实现。
 * <p>
 * 官方引擎原语（依赖 hassium.mixin accessor）与磁盘缓存读写全部留在此处；
 * 引擎接口（LightEngineHooks）在 Promethium MOD 内，Hassium 无编译依赖——本类不
 * implements 接口，由 {@link PromethiumLightBridge} 经反射 Proxy 包装注入，不反向依赖。
 */
public final class HassiumLightHooks {

    public static final HassiumLightHooks INSTANCE = new HassiumLightHooks();

    private HassiumLightHooks() {}

    public int safeRunLightUpdates(LevelLightEngine lightEngine) {
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

    public void ensureColumnDataLayers(ClientLevel level, LevelLightEngine lightEngine,
                                       ChunkPos chunkPos, int bottomSection, int topSection) {
        for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
            SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);
            lightEngine.updateSectionStatus(sectionPos, false);
            level.setSectionDirtyWithNeighbors(chunkPos.x, sectionY, chunkPos.z);
        }
    }

    public void ensureNeighborDataLayers(ClientLevel level, LevelLightEngine lightEngine,
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

    public void pullLightFromNeighborEdges(ClientLevel level, ChunkPos chunkPos,
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

    public void updateCacheWithLightData(ClientLevel level, ChunkPos chunkPos, CompoundTag cachedNbt) {
        try {
            ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
            if (storage == null) return;

            CompoundTag nbt = cachedNbt;
            if (nbt == null) {
                // fallback：从磁盘读取
                byte[] cachedData = storage.loadAndDecompress(chunkPos);
                if (cachedData == null) return;
                nbt = ChunkDiskCodec.bytesToNbt(cachedData);
                if (nbt == null) return;
            }

            // 刚重算完：始终用引擎态覆盖磁盘（勿因旧 is_light_on=1 提前 return，
            // SectionDelta 曾留下「flag=1 + 残缺 light」时会永久跳过回写）
            LevelLightEngine lightEngine = level.getLightEngine();
            LayerLightEventListener skyListener =
                    lightEngine.getLayerListener(LightLayer.SKY);
            LayerLightEventListener blockListener =
                    lightEngine.getLayerListener(LightLayer.BLOCK);

            int minSection = LevelHeightCompat.getMinSection(level);
            int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);
            net.minecraft.nbt.ListTag sectionsList = CompoundTagCompat.getList(nbt, "sections");

            boolean hasAnyLight = false;
            for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                int idx = sectionY - minSection;
                if (idx >= sectionsList.size()) break;

                net.minecraft.nbt.Tag t = sectionsList.get(idx);
                if (!(t instanceof net.minecraft.nbt.CompoundTag sectionTag)) continue;

                SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);

                DataLayer skyData = skyListener.getDataLayerData(sectionPos);
                if (skyData != null && !skyData.isEmpty()) {
                    sectionTag.putByteArray("sky_light", skyData.getData().clone());
                    hasAnyLight = true;
                } else {
                    sectionTag.remove("sky_light");
                }

                DataLayer blockData = blockListener.getDataLayerData(sectionPos);
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

    public CompoundTag loadChunkNbtFromCache(ChunkPos pos) {
        return ClientChunkHandler.loadChunkNbtFromCache(pos);
    }
}
