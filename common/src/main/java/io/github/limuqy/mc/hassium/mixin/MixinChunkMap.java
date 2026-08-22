package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#if MC_VER < MC_1_21_1
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
#endif
#if MC_VER < MC_1_21_1
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
#else
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
#endif

/**
 * 影子端 ChunkMap：注入表命中则短路 {@code scheduleChunkLoad} 为
 * {@code ImposterProtoChunk}（对齐原版读盘 FULL 柱），避免 getChunkFuture
 * 把已 decode 的柱顶成噪声地形。SeedGen {@code generateChunk} 期间
 * {@link ShadowChunkMapCompat#isWorldgenAllowed()} 为 true，本拦截不抢生成柱。
 * <p>
 * 探活：FULL 注入柱 persisted=FULL，不能赌金字塔从 FULL 再跑 LIGHT 作为唯一算光路径
 * （邻柱无盘会 GENERATION_PYRAMID）。光仍由 {@code ShadowLightCompute} 提交官方
 * {@code initializeLight}+{@code lightChunk}。非 EMPTY 地形步在注入票路径上透传。
 */
@Mixin(net.minecraft.server.level.ChunkMap.class)
public class MixinChunkMap {

#if MC_VER < MC_1_21_1
    @Shadow
    private ThreadedLevelLightEngine lightEngine;

    @Unique
    private ClientboundLevelChunkWithLightPacket hassium$dummyChunkPacket;

    /**
     * 1.20.1：专用服用独立、已填充的 holder，跳过 {@code new ClientboundLevelChunkWithLightPacket}
     *（组包成本）。与压缩无关；{@link MixinServerPlayer} 登记 pending 并 cancel 发送。
     */
    @ModifyVariable(method = "playerLoadedChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private MutableObject<ClientboundLevelChunkWithLightPacket> hassium$skipVanillaPacketBuild(
            MutableObject<ClientboundLevelChunkWithLightPacket> holder,
            ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> ignored,
            LevelChunk chunk) {
        if (RuntimeServerContext.isShadowServerContext()
                || player == null
                || !ServerChunkPushManager.shouldPaceChunkSends()) {
            return holder;
        }
        MutableObject<ClientboundLevelChunkWithLightPacket> isolated = new MutableObject<>();
        isolated.setValue(hassium$dummyPacket(chunk));
        return isolated;
    }

    @Unique
    private ClientboundLevelChunkWithLightPacket hassium$dummyPacket(LevelChunk chunk) {
        if (hassium$dummyChunkPacket == null && chunk != null && lightEngine != null) {
            hassium$dummyChunkPacket = new ClientboundLevelChunkWithLightPacket(
                    chunk, lightEngine, null, null);
        }
        return hassium$dummyChunkPacket;
    }
#endif

#if MC_VER < MC_1_21_1
    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"), cancellable = true)
    private void hassium$shortCircuitInjectLoad(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir) {
        LevelChunk injected = hassium$injectedIfShortCircuit(pos);
        if (injected == null) {
            return;
        }
        ImposterProtoChunk wrapped = ShadowChunkMapCompat.asImposter(injected);
        cir.setReturnValue(CompletableFuture.completedFuture(Either.left(wrapped)));
    }

    @Inject(method = "scheduleChunkGeneration", at = @At("HEAD"), cancellable = true)
    private void hassium$passthroughInjectWorldgen(ChunkHolder holder, ChunkStatus status,
            CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir) {
        ChunkAccess available = hassium$passthroughChunk(holder, status);
        if (available == null) {
            return;
        }
        cir.setReturnValue(CompletableFuture.completedFuture(Either.left(available)));
    }
    // 1.20.5–1.20.6 的 ChunkResult/ChunkHolder 中间层注入已随版本支持裁剪删除（API 自 1.21.1 起变化）
#else
    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"), cancellable = true)
    private void hassium$shortCircuitInjectLoad(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LevelChunk injected = hassium$injectedIfShortCircuit(pos);
        if (injected == null) {
            return;
        }
        cir.setReturnValue(ShadowChunkMapCompat.completedImposter(injected));
    }

    @Inject(method = "applyStep", at = @At("HEAD"), cancellable = true)
    private void hassium$passthroughInjectWorldgen(GenerationChunkHolder holder, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (step == null) {
            return;
        }
        ChunkStatus status = step.targetStatus();
        if (!ShadowChunkMapCompat.shouldPassthroughGenerationStep(
                RuntimeServerContext.isShadowServerContext(),
                ShadowChunkMapCompat.isWorldgenAllowed(),
                ShadowChunkMapCompat.isEmptyStatus(status))) {
            return;
        }
        ChunkAccess parent = holder.getChunkIfPresentUnchecked(status.getParent());
        if (parent == null) {
            return;
        }
        cir.setReturnValue(CompletableFuture.completedFuture(parent));
    }
#endif

    @Unique
    private static LevelChunk hassium$injectedIfShortCircuit(ChunkPos pos) {
        if (pos == null || !RuntimeServerContext.isShadowServerContext()) {
            return null;
        }
        if (ShadowChunkMapCompat.isWorldgenAllowed()) {
            // SeedGen 生成中：仅当该 pos 已在注入表时抢 load，避免生成任务把注入柱顶掉
            ShadowSeedServer server = ShadowChunkMapCompat.shadowServerOrNull();
            return server == null ? null : server.injectedChunk(pos.x, pos.z);
        }
        ShadowSeedServer server = ShadowChunkMapCompat.shadowServerOrNull();
        if (server == null) {
            return null;
        }
        LevelChunk injected = server.injectedChunk(pos.x, pos.z);
        if (!ShadowChunkMapCompat.shouldShortCircuitScheduleLoad(true, injected != null)) {
            return null;
        }
        return injected;
    }

#if MC_VER < MC_1_21_1
    @Unique
    private static ChunkAccess hassium$passthroughChunk(ChunkHolder holder, ChunkStatus status) {
        if (holder == null || !RuntimeServerContext.isShadowServerContext()
                || ShadowChunkMapCompat.isWorldgenAllowed()) {
            return null;
        }
        if (!ShadowChunkMapCompat.shouldPassthroughGenerationStep(true, false,
                ShadowChunkMapCompat.isEmptyStatus(status))) {
            return null;
        }
        return holder.getLastAvailable();
    }
#endif
}
