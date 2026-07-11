package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.SectionScanTask;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端光照重算 Mixin
 * <p>
 * 当服务端剥离了光照数据（空 BitSet），客户端需要本地重算光照。
 * 光源扫描在后台线程池并行执行，光照引擎更新通过 MainThreadDispatcher 回到主线程。
 * <p>
 * Phase 2 优化：
 * - 每帧限制：避免大量区块同时重算导致卡顿
 * - 批量光源通知：减少主线程上下文切换
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
     * 上一次处理的帧号
     */
    @Unique
    private static volatile long hassium$lastFrameTime = 0;

    /**
     * 在 handleLevelChunkWithLight 处理完成后，检测光照是否被剥离并异步重算
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

        // 检查每帧限制
        int maxPerFrame = HassiumConfigService.getInstance().getMaxLightRecomputePerFrame();
        long currentTime = System.currentTimeMillis();

        // 如果距离上次处理超过 50ms（约 3 帧），重置计数器
        if (currentTime - hassium$lastFrameTime > 50) {
            hassium$processedThisFrame.set(0);
            hassium$lastFrameTime = currentTime;
        }

        // 检查是否超过每帧限制
        if (hassium$processedThisFrame.get() >= maxPerFrame) {
            // 延迟到下一帧处理（提交到主线程调度器）
            MainThreadDispatcher.execute(() -> {
                hassium$asyncRecomputeLight(chunk, chunkPos);
            }, chunkPos, TaskCategory.SAFE_TO_CANCEL);
            return;
        }

        hassium$processedThisFrame.incrementAndGet();
        hassium$asyncRecomputeLight(chunk, chunkPos);
    }

    /**
     * 异步光照重算（使用 CompletableFuture 替代两级提交，避免线程饥饿）
     * <p>
     * 1. 后台线程并行扫描各 section 的光源位置
     * 2. 全部完成后，主线程应用光照引擎更新
     */
    @Unique
    private void hassium$asyncRecomputeLight(LevelChunk chunk, ChunkPos chunkPos) {
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null) {
            // 执行器未初始化，回退到主线程同步处理
            hassium$recomputeLightSync(chunk, chunkPos);
            return;
        }

#if MC_VER < MC_1_21_4
        int bottomSection = level.getMinSection();
        int topSection = level.getMaxSection();
#else
        int bottomSection = level.getMinSectionY();
        int topSection = level.getMaxSectionY() + 1; // getMaxSectionY is inclusive, +1 for exclusive upper bound
#endif

        // 收集需要扫描的 section
        List<SectionScanTask> scanTasks = new ArrayList<>();
        for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (!section.hasOnlyAir()) {
                scanTasks.add(new SectionScanTask(chunkPos, sectionY, section));
            }
        }

        if (scanTasks.isEmpty()) {
            // 全是空气，只需设置状态和传播
            MainThreadDispatcher.execute(
                    () -> hassium$applyLightEngine(chunk, chunkPos, bottomSection, topSection, new ArrayList<>()),
                    chunkPos);
            return;
        }

        // 并行扫描光源位置（使用 CompletableFuture，不占用额外线程做阻塞等待）
        // 注意：使用 executor::execute 而非 executor::submit，
        // supplyAsync 需要 Executor 接口的 execute(Runnable) 语义
        @SuppressWarnings("unchecked")
        CompletableFuture<List<BlockPos>>[] futures = scanTasks.stream()
                .map(task -> CompletableFuture.supplyAsync(
                        () -> hassium$scanLightSources(task),
                        executor::execute))
                .toArray(CompletableFuture[]::new);

        // 全部扫描完成后，合并结果并调度回主线程
        CompletableFuture.allOf(futures).whenComplete((v, throwable) -> {
            List<BlockPos> allLightSources = new ArrayList<>();
            for (CompletableFuture<List<BlockPos>> future : futures) {
                try {
                    List<BlockPos> sources = future.get();
                    if (sources != null) {
                        allLightSources.addAll(sources);
                    }
                } catch (Exception e) {
                    Constants.LOG.warn("Hassium: Light source scan task failed", e);
                }
            }

            // 回到主线程应用光照引擎更新
            MainThreadDispatcher.execute(
                    () -> hassium$applyLightEngine(chunk, chunkPos, bottomSection, topSection, allLightSources),
                    chunkPos,
                    TaskCategory.SAFE_TO_CANCEL);
        }).exceptionally(ex -> {
            Constants.LOG.error("Hassium: Light recompute pipeline failed for {}", chunkPos, ex);
            return null;
        });
    }

    /**
     * 扫描单个 section 的光源位置（后台线程执行）
     */
    @Unique
    private List<BlockPos> hassium$scanLightSources(SectionScanTask task) {
        List<BlockPos> lightSources = new ArrayList<>();
        int minBlockY = task.sectionY() << 4;
        int chunkBlockX = task.chunkPos().x << 4;
        int chunkBlockZ = task.chunkPos().z << 4;

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (task.section().getBlockState(x, y, z).getLightEmission() > 0) {
                        lightSources.add(new BlockPos(chunkBlockX + x, minBlockY + y, chunkBlockZ + z));
                    }
                }
            }
        }
        return lightSources;
    }

    /**
     * 应用光照引擎更新（主线程执行）
     * <p>
     * 批量优化：先设置所有 section 状态，再批量通知光源，最后统一传播
     */
    @Unique
    private void hassium$applyLightEngine(LevelChunk chunk, ChunkPos chunkPos,
                                           int bottomSection, int topSection,
                                           List<BlockPos> lightSources) {
        try {
            LevelLightEngine lightEngine = level.getLightEngine();

            // 启用光照列
            lightEngine.setLightEnabled(chunkPos, true);

            // 批量设置每个 section 的状态
            for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
                SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
                lightEngine.updateSectionStatus(sectionPos, section.hasOnlyAir());
            }

            // 批量通知所有光源位置
            for (BlockPos pos : lightSources) {
                lightEngine.checkBlock(pos);
            }

            // 统一传播光照
            lightEngine.propagateLightSources(chunkPos);

            Constants.LOG.debug("Hassium: Recomputed light for chunk {} ({} light sources)",
                    chunkPos, lightSources.size());
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to apply light engine for chunk {}", chunkPos, e);
        }
    }

    /**
     * 同步光照重算（回退方案，执行器未初始化时使用）
     */
    @Unique
    private void hassium$recomputeLightSync(LevelChunk chunk, ChunkPos chunkPos) {
#if MC_VER < MC_1_21_4
        int bottomSection = level.getMinSection();
        int topSection = level.getMaxSection();
#else
        int bottomSection = level.getMinSectionY();
        int topSection = level.getMaxSectionY() + 1; // getMaxSectionY is inclusive, +1 for exclusive upper bound
#endif
        List<BlockPos> lightSources = new ArrayList<>();

        for (int sectionY = bottomSection; sectionY < topSection; sectionY++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (section.hasOnlyAir()) continue;

            int minBlockY = sectionY << 4;
            int chunkBlockX = chunkPos.x << 4;
            int chunkBlockZ = chunkPos.z << 4;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (section.getBlockState(x, y, z).getLightEmission() > 0) {
                            lightSources.add(new BlockPos(chunkBlockX + x, minBlockY + y, chunkBlockZ + z));
                        }
                    }
                }
            }
        }

        hassium$applyLightEngine(chunk, chunkPos, bottomSection, topSection, lightSources);
    }
}
