package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
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
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return;
        }
        applyLightEngine(level, chunk, chunkPos);
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

            lightEngine.propagateLightSources(chunkPos);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0) == (dz == 0)) {
                        continue;
                    }
                    int nx = chunkPos.x + dx;
                    int nz = chunkPos.z + dz;
                    if (level.getChunkSource().getChunkNow(nx, nz) != null) {
                        lightEngine.propagateLightSources(new ChunkPos(nx, nz));
                    }
                }
            }

            pullLightFromNeighborEdges(level, chunkPos, bottomSection, topSection);
            Constants.LOG.debug("Hassium: Recomputed light for chunk {}", chunkPos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply light engine for chunk {}", chunkPos, e);
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
