package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#if MC_VER < MC_1_21_1
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.apache.commons.lang3.mutable.MutableObject;
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
 * 影子端接管：已 materialize / 磁盘基线的区块短路进 {@code scheduleChunkLoad}，
 * 禁止有缓存时再 worldgen。无基线柱交给原版 ChunkMap 生成与光照流水线
 * （门控开）或悬置等 pull（门控关）。
 */
@Mixin(net.minecraft.server.level.ChunkMap.class)
public class MixinChunkMap {

    @Shadow
    @Final
    ServerLevel level;

#if MC_VER < MC_1_21_1
    @Shadow
    private ThreadedLevelLightEngine lightEngine;

    @Unique
    private ClientboundLevelChunkWithLightPacket hassium$dummyChunkPacket;

    /**
     * 1.20.1：专用服用独立、已填充的 holder，跳过 {@code new ClientboundLevelChunkWithLightPacket}
     *（组包成本）。与压缩无关；{@link MixinServerPlayer} 登记 pending 并 cancel 发送。
     * <p>
     * 影子虚拟玩家上下文不走本钩子：{@code playerLoadedChunk} 在 HEAD 被
     * {@link #hassium$shadowBridgeLoadedChunk} cancel 并转统一 Compare+Pull 桥。
     */
    @ModifyVariable(method = "playerLoadedChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private MutableObject<ClientboundLevelChunkWithLightPacket> hassium$skipVanillaPacketBuild(
            MutableObject<ClientboundLevelChunkWithLightPacket> holder,
            ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> ignored,
            LevelChunk chunk) {
        if (player == null
                || !PlayerCompressionTracker.isCompressionEnabled(player)) {
            return holder;
        }
        MutableObject<ClientboundLevelChunkWithLightPacket> isolated = new MutableObject<>();
        isolated.setValue(hassium$dummyPacket(chunk));
        return isolated;
    }

    /**
     * 影子虚拟玩家交付桥（§6 节点 E/G → I）：原版链产出的柱（type126 读盘命中 /
     * 本地生成门控放行的生成柱）注入影子表形成本地基线，携带基线发统一比对请求；
     * 服务端裁决 UNCHANGED/DELTA/FULL 后经既有响应路径落地。不走原版包发送。
     * <p>
     * 无数据被悬置的柱（worldgen 压制）FULL future 永不完成，不会进入本钩子——
     * 其交付由悬置登记的空基线请求 FULL 响应承担。
     */
    @Inject(method = "playerLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$shadowBridgeLoadedChunk(ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> holder, LevelChunk chunk,
            CallbackInfo ci) {
        if (!RuntimeServerContext.isShadowServerContext() || chunk == null) {
            return;
        }
        hassium$notifyShadowMaterialized(chunk);
        ci.cancel();
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
        LevelChunk loaded = hassium$chunkForScheduleLoad(pos);
        if (loaded != null) {
            ImposterProtoChunk wrapped = ShadowChunkMapCompat.asImposter(loaded);
            cir.setReturnValue(CompletableFuture.completedFuture(Either.left(wrapped)));
            return;
        }
        if (hassium$shadowSuppressGeneration(pos)) {
            // 影子虚拟玩家 tracking 选中且无数据：返回悬置 future，等待 pull 响应。
            // pull 响应到达后 injectChunk 注入真实数据 → completeSuspendedLoad 放行
            // 原版链 → playerLoadedChunk(有数据) → onChunkMaterialized → compare-pull。
            // 超时由 ShadowChunkMapCompat.sweepSuspendedTimeouts 兜底（完成为空柱）。
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> suspended =
                    new CompletableFuture<>();
            ShadowChunkMapCompat.registerSuspendedLoad(hassium$shadowDimension(), pos, suspended);
            cir.setReturnValue(suspended);
            return;
        }
        // 未命中：无注入、无盘。门控开则原版 worldgen；关则上面已悬置。
        // 有盘基线已由 hassium$chunkForScheduleLoad 短路，禁止再生成。
    }

    // 1.20.5–1.20.6 的 ChunkResult/ChunkHolder 中间层注入已随版本支持裁剪删除（API 自 1.21.1 起变化）
#else
    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"), cancellable = true)
    private void hassium$shortCircuitInjectLoad(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LevelChunk loaded = hassium$chunkForScheduleLoad(pos);
        if (loaded != null) {
            cir.setReturnValue(ShadowChunkMapCompat.completedImposter(loaded));
            return;
        }
        if (hassium$shadowSuppressGeneration(pos)) {
            // 悬置 future 等待 pull 响应（与 1.20.1 同款）。
            CompletableFuture<ChunkAccess> suspended = new CompletableFuture<>();
            ShadowChunkMapCompat.registerSuspendedLoad(hassium$shadowDimension(), pos, suspended);
            cir.setReturnValue(suspended);
            return;
        }
        // 未命中：无注入、无盘。门控开则原版 worldgen；关则上面已悬置。
        // 产出经 onChunkReadyToSend → onChunkMaterialized（1.21+ 已无 playerLoadedChunk）。
    }

    /**
     * 1.21.1：FULL+sendSync 就绪即进 tracking 待发队列。影子 {@code runMainLoop}
     * 不跑 {@code MinecraftServer} 的 send-chunks 泵，必须在这里转 Compare+Pull 桥，
     * 否则 locallyGenerated 恒 0、生成柱永不 persist。
     */
#if MC_VER < MC_1_21_2
    @Inject(method = "onChunkReadyToSend(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("HEAD"))
    private void hassium$shadowBridgeReadyToSend(LevelChunk chunk, CallbackInfo ci) {
        hassium$notifyShadowMaterialized(chunk);
    }
#else
    @Inject(method = "onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("HEAD"))
    private void hassium$shadowBridgeReadyToSend(net.minecraft.server.level.ChunkHolder holder,
            LevelChunk chunk, CallbackInfo ci) {
        hassium$notifyShadowMaterialized(chunk);
    }
#endif

#endif

    /** 影子虚拟玩家交付桥：1.20.1={@code playerLoadedChunk}，1.21+={@code onChunkReadyToSend}。 */
    @Unique
    private void hassium$notifyShadowMaterialized(LevelChunk chunk) {
        if (!RuntimeServerContext.isShadowServerContext() || chunk == null) {
            return;
        }
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowTrackingSession.getInstance()
                    .onChunkMaterialized(hassium$shadowDimension(), chunk.getPos(), chunk);
        } catch (Throwable t) {
            io.github.limuqy.mc.hassium.Constants.LOG.error(
                    "[SHADOW_TRACK] onChunkMaterialized failed ({}, {})",
                    chunk.getPos().x, chunk.getPos().z, t);
        }
    }

    /** 本 ChunkMap 所属维度（影子上下文；null 回落 OVERWORLD，与既有钩子同口径）。 */
    @Unique
    private String hassium$shadowDimension() {
        String dimension = LevelCompat.getDimensionId(this.level);
        if (dimension == null) {
            dimension = io.github.limuqy.mc.hassium.utils.DimensionKey.OVERWORLD;
        }
        return dimension;
    }

    /**
     * 影子虚拟玩家 tracking 的选柱登记 + worldgen 压制判定（1.20.1 / 1.21.1+ 共用）。
     * <p>
     * 返回 true = 抑制 worldgen，改用悬置 future 等待 pull 响应（与原版读盘等 IOWorker
     * 同模式：scheduleChunkLoad 返回未完成 future，数据到达后 completeSuspendedLoad 放行）。
     * 仅影子上下文且非 worldgen 窗口生效；登记在注入表未命中时进行——磁盘命中柱
     * 同样登记（读盘 hash 由 MixinRegionFile 同步回填，分类延迟一个簿记周期即可携带基线，
     * 服务端裁决 UNCHANGED/DELTA/FULL）。
     * <p>
     * 本地生成门控通过（客户端本地生成开启 + 服务端 SeedGen 开启 + 真实 seed 到达）：
     * 选中<strong>无缓存基线</strong>的柱由虚拟玩家 tracking 触发影子原版生成链，
     * 不压制；有注入/磁盘基线时 {@link #hassium$chunkForScheduleLoad} 已短路，不会 worldgen。
     * 权威窗内生成柱先 compare-pull，再算光交付。
     */
    @Unique
    private boolean hassium$shadowSuppressGeneration(ChunkPos pos) {
        if (!RuntimeServerContext.isShadowServerContext() || pos == null) {
            return false;
        }
        if (ShadowChunkMapCompat.isWorldgenAllowed()) {
            // SeedGen worldgen 窗口：依赖柱不登记、不压制
            return false;
        }
        if (io.github.limuqy.mc.hassium.network.seedgen.SeedGenExecutor.getInstance()
                .isGenerationGateOpen()) {
            // 门控通过：虚拟玩家触发原版生成链（真实种子），产出经
            // playerLoadedChunk（1.20.1）/ onChunkReadyToSend（1.21+）桥转
            // 统一 Compare+Pull（生成内容作基线，服务端裁决）
            return false;
        }
        String dimension = hassium$shadowDimension();
        io.github.limuqy.mc.hassium.network.seedgen.ShadowTrackingSession.getInstance()
                .onChunkSelected(dimension, pos.x, pos.z);
        return true;
    }

    @Unique
    private LevelChunk hassium$chunkForScheduleLoad(ChunkPos pos) {
        if (pos == null || !RuntimeServerContext.isShadowServerContext()) {
            return null;
        }
        ShadowSeedServer server = ShadowChunkMapCompat.shadowServerOrNull();
        if (server == null) {
            return null;
        }
        return ShadowChunkMapCompat.existingColumnForScheduleLoad(
                server, hassium$shadowDimension(), pos);
    }

    /**
     * 影子原版 {@code ChunkMap.save} 与 flush 线程 {@code ChunkSerializer.write}
     * 抢同一份 PalettedContainer → 1.20.1 ThreadingDetector。与
     * {@link io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute#withChunkLock}
     * 同一把可重入锁。{@code save} 内部 catch 后仍走 RETURN，成对解锁。
     */
    @Unique
    private static final ThreadLocal<Integer> hassium$saveLockDepth =
            ThreadLocal.withInitial(() -> 0);

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"))
    private void hassium$lockShadowSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (!RuntimeServerContext.isShadowServerContext() || chunk == null) {
            return;
        }
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.lockChunk(chunk.getPos());
        hassium$saveLockDepth.set(hassium$saveLockDepth.get() + 1);
    }

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("RETURN"))
    private void hassium$unlockShadowSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        int depth = hassium$saveLockDepth.get();
        if (depth <= 0) {
            return;
        }
        hassium$saveLockDepth.set(depth - 1);
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.unlockChunk(chunk.getPos());
    }

}
