package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断（{@code debug.lightVerify} 门控，只打点、零行为改动）：统计每柱
 * {@code ThreadedLevelLightEngine.lightChunk(ChunkAccess, boolean)} 的调用次数与调用面。
 * <p>
 * 目的：判定注入柱是否被**双写光引擎**——原版 holder 的 {@code ChunkStatus.LIGHT} 任务
 * （经 {@code completeSuspendedLoad} 的 ImposterProtoChunk）与 Hassium 自建屏障
 * （{@code ShadowLightCompute.startLightBarrier} 在 native clone 上）是否对同一柱各跑一次。
 * 若同柱出现两次且 {@code class=} 不同，即双写实锤（两个写者交错 → 终态不确定）。
 * <p>
 * 关闭门控时第一行即返回。
 */
@Mixin(ThreadedLevelLightEngine.class)
public class MixinThreadedLevelLightEngine {

    @Inject(method = "lightChunk(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)"
                    + "Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), require = 1)
    private void hassium$traceLightChunk(ChunkAccess chunk, boolean lit,
                                         CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!DebugLogger.isEnabled(DebugLogger.LogType.LIGHT) || chunk == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        DebugLogger.info(DebugLogger.LogType.LIGHT,
                "[LIGHT_CALL] pos=({},{}) lit={} class={} thread={}",
                pos.x(), pos.z(), lit, chunk.getClass().getSimpleName(),
                Thread.currentThread().getName());
        // T3 探针 Hook 2 重挂（2026-10-05）：本注入点是全部 lightChunk 的唯一咽喉——
        // 影子金字塔（ChunkStatus.LIGHT，ProtoChunk）与 Hassium 屏障（completeNativeLight，
        // native clone）都过这里；原 submitLightChunkLocked 挂点随光桥重构失联。
        // 探针内部再查 isProbeChunk，非探针柱零开销。
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightProbe.onBeforeLightChunk(
                io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get(),
                (net.minecraft.server.level.ThreadedLevelLightEngine) (Object) this,
                chunk, null);
    }
}
