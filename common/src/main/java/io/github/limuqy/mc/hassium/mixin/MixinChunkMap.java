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
 * 影子端只接管两处：已 materialize 的区块读盘结果进入原版 {@code scheduleChunkLoad}，
 * 其余区块完全交给原版 ChunkMap 生成与光照流水线。玩家视距、halo、主动 admission 不在此实现。
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
        io.github.limuqy.mc.hassium.network.seedgen.ShadowTrackingSession.getInstance()
                .onChunkMaterialized(hassium$shadowDimension(), chunk.getPos(), chunk);
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
        // 未命中不 cancel：空槽由 MixinRegionFile 返回 null → createEmpty + 透传，
        // 不得在这里 loadFromDisk（FULL 票邻柱会同步解压整圈）。
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
        }
        // 未命中不 cancel：MixinRegionFile 对非 126 返回 null，避免 completedFuture(null) NPE。
    }

#endif

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
     * 选中缺失柱由虚拟玩家 tracking 触发影子原版生成链（§6 节点 F/G，真实种子），
     * 不压制、不登记 pull，交付仍走 SeedGenExecutor 校验/publish 既有路径。
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
            // 门控通过：虚拟玩家触发原版生成链（真实种子），产出经 playerLoadedChunk
            // 桥转统一 Compare+Pull（生成内容作基线，服务端裁决）
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
        LevelChunk injected = server.injectedChunk(hassium$shadowDimension(), pos.x, pos.z);
        if (injected != null) {
            return injected;
        }
        // 无 materialized 区块：不 cancel，让原版 IOWorker/type126 读盘或生成链决定下一步。
        return null;
    }

}
