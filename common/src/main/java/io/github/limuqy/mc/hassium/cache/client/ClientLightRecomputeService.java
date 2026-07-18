package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端光照重算（从 Mixin 抽出，避免 Mixin 类上出现 public static）。
 * <p>
 * 剥离光照包到达后延后到 {@link MainThreadDispatcher}；每帧限流，超额进溢出队列。
 */
public final class ClientLightRecomputeService {

    private static final AtomicInteger PROCESSED_THIS_FRAME = new AtomicInteger(0);
    private static volatile long lastFrameTimeMs = 0L;
    private static final ConcurrentLinkedQueue<ChunkPos> OVERFLOW = new ConcurrentLinkedQueue<>();

    private ClientLightRecomputeService() {}

    /** 安排一次光照重算（可在任意线程调用）。 */
    public static void schedule(ChunkPos chunkPos) {
        MainThreadDispatcher.execute(
                () -> tryApply(chunkPos),
                chunkPos,
                TaskCategory.SAFE_TO_CANCEL);
    }

    /** 上一帧溢出任务 → 本帧 flush 前入队（由 MixinClientTick 调用）。 */
    public static void promoteOverflow() {
        ChunkPos pos;
        while ((pos = OVERFLOW.poll()) != null) {
            schedule(pos);
        }
    }

    /** 断连时清空。 */
    public static void clear() {
        OVERFLOW.clear();
        PROCESSED_THIS_FRAME.set(0);
    }

    private static void tryApply(ChunkPos chunkPos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return;
        }

        int maxPerFrame = HassiumConfigService.getInstance().getMaxLightRecomputePerFrame();
        long now = System.currentTimeMillis();
        if (now - lastFrameTimeMs > 50) {
            PROCESSED_THIS_FRAME.set(0);
            lastFrameTimeMs = now;
        }

        if (PROCESSED_THIS_FRAME.get() >= maxPerFrame) {
            OVERFLOW.offer(chunkPos);
            return;
        }

        PROCESSED_THIS_FRAME.incrementAndGet();
        applyLightEngine(level, chunk, chunkPos);
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
