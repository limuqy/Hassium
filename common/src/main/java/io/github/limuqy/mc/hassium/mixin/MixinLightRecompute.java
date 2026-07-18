package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端光照重算 Mixin
 * <p>
 * 当服务端剥离了光照数据（空 BitSet），客户端本地重算光照。
 * 直接走原版 {@link LevelLightEngine#propagateLightSources}（内部已用 maybeHas 找光源），
 * 并对已加载正交四邻再 propagate；最后在共享边界 checkBlock 拉取邻区已有天空光/方块光
 * （propagate 只从天空光源列播种，无法把邻区洞穴内已传播的天空光拉进无露天新区）。
 * <p>
 * 每帧限流：避免进服批量加载时主线程尖峰。
 */
@Mixin(ClientPacketListener.class)
public class MixinLightRecompute {

    @Shadow
    private ClientLevel level;

    /**
     * 当前帧已处理的光照重算区块数（每帧重置）
     */
    @Unique
    private static final AtomicInteger hassium$processedThisFrame = new AtomicInteger(0);

    /**
     * 上一次处理的时间戳（用于帧窗口重置）
     */
    @Unique
    private static volatile long hassium$lastFrameTime = 0;

    /**
     * 在 handleLevelChunkWithLight 处理完成后，检测光照是否被剥离并重算
     */
    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void hassium$onHandleChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!HassiumConfigService.getInstance().isLightStripEnabled()) {
            return;
        }
        if (level == null) {
            return;
        }

        // 检测光照被剥离：LightData 的 4 个 BitSet 全为空
        var lightData = packet.getLightData();
        if (!lightData.getSkyYMask().isEmpty() || !lightData.getBlockYMask().isEmpty()
                || !lightData.getEmptySkyYMask().isEmpty() || !lightData.getEmptyBlockYMask().isEmpty()) {
            return; // 有光照数据，不需要重算
        }

        int chunkX = packet.getX();
        int chunkZ = packet.getZ();
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }

        int maxPerFrame = HassiumConfigService.getInstance().getMaxLightRecomputePerFrame();
        long currentTime = System.currentTimeMillis();

        // 如果距离上次处理超过 50ms（约 3 帧），重置计数器
        if (currentTime - hassium$lastFrameTime > 50) {
            hassium$processedThisFrame.set(0);
            hassium$lastFrameTime = currentTime;
        }

        // 超过每帧限制：延迟到主线程调度器下一帧再处理
        if (hassium$processedThisFrame.get() >= maxPerFrame) {
            MainThreadDispatcher.execute(
                    () -> hassium$applyLightEngine(chunk, chunkPos),
                    chunkPos,
                    TaskCategory.SAFE_TO_CANCEL);
            return;
        }

        hassium$processedThisFrame.incrementAndGet();
        // 已在主线程（handleLevelChunkWithLight），直接应用
        hassium$applyLightEngine(chunk, chunkPos);
    }

    /**
     * 应用光照引擎更新（主线程执行）
     * <p>
     * 启用光照列 → 更新 section 状态并脏标记 → propagate 本区 →
     * 对已加载正交四邻再 propagate → 边界 checkBlock 拉取邻区已有光。
     * 光源查找交给原版 {@code propagateLightSources}（内部 maybeHas）。
     */
    @Unique
    private void hassium$applyLightEngine(LevelChunk chunk, ChunkPos chunkPos) {
        try {
            if (level == null) {
                return;
            }

#if MC_VER < MC_1_21_2
            int bottomSection = level.getMinSection();
            int topSection = level.getMaxSection();
#else
            int bottomSection = level.getMinSectionY();
            int topSection = level.getMaxSectionY() + 1; // getMaxSectionY is inclusive
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

            // 四邻已加载则再 propagate，把邻区方块光源 / 天空光源列灌进新区
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0) == (dz == 0)) {
                        continue; // 只要正交四邻
                    }
                    int nx = chunkPos.x + dx;
                    int nz = chunkPos.z + dz;
                    if (level.getChunkSource().getChunkNow(nx, nz) != null) {
                        lightEngine.propagateLightSources(new ChunkPos(nx, nz));
                    }
                }
            }

            // 无露天新区：邻区洞穴内已有天空光不会因 propagate 自动灌入，
            // 需在共享边界对新区格子 checkBlock（light==0 → PULL_LIGHT_IN_ENTRY）
            hassium$pullLightFromNeighborEdges(chunkPos);

            Constants.LOG.debug("Hassium: Recomputed light for chunk {}", chunkPos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply light engine for chunk {}", chunkPos, e);
        }
    }

    /**
     * 在新区与已加载四邻的共享边界上拉取邻区光照。
     * <p>
     * 仅当邻区边界格天空光或方块光大于 0 时，对本区对应边界格 checkBlock，
     * 触发原版 light==0 时的 PULL 逻辑，避免无光边界全高扫一遍。
     */
    @Unique
    private void hassium$pullLightFromNeighborEdges(ChunkPos chunkPos) {
        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener sky = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener block = lightEngine.getLayerListener(LightLayer.BLOCK);
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos ourPos = new BlockPos.MutableBlockPos();

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();

        // 东 (+X)
        if (level.getChunkSource().getChunkNow(chunkPos.x + 1, chunkPos.z) != null) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    neighborPos.set(minX + 16, y, minZ + z);
                    if (sky.getLightValue(neighborPos) > 0 || block.getLightValue(neighborPos) > 0) {
                        ourPos.set(minX + 15, y, minZ + z);
                        lightEngine.checkBlock(ourPos);
                    }
                }
            }
        }
        // 西 (-X)
        if (level.getChunkSource().getChunkNow(chunkPos.x - 1, chunkPos.z) != null) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    neighborPos.set(minX - 1, y, minZ + z);
                    if (sky.getLightValue(neighborPos) > 0 || block.getLightValue(neighborPos) > 0) {
                        ourPos.set(minX, y, minZ + z);
                        lightEngine.checkBlock(ourPos);
                    }
                }
            }
        }
        // 南 (+Z)
        if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z + 1) != null) {
            for (int x = 0; x < 16; x++) {
                for (int y = minY; y < maxY; y++) {
                    neighborPos.set(minX + x, y, minZ + 16);
                    if (sky.getLightValue(neighborPos) > 0 || block.getLightValue(neighborPos) > 0) {
                        ourPos.set(minX + x, y, minZ + 15);
                        lightEngine.checkBlock(ourPos);
                    }
                }
            }
        }
        // 北 (-Z)
        if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z - 1) != null) {
            for (int x = 0; x < 16; x++) {
                for (int y = minY; y < maxY; y++) {
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
