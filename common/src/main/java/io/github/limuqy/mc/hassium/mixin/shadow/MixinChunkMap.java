package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.server.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.protocol.Packet;
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
     * 影子虚拟玩家交付桥（§6 节点 E/G → I）：原版链产出的柱注入影子表形成本地基线；
     * seedGen 权威窗走 compare-before-deliver（先 mark，响应前不桥接）。
     * 服务端裁决 UNCHANGED/DELTA/FULL 后经既有响应路径落地。
     */
    @Inject(method = "playerLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$shadowBridgeLoadedChunk(ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> holder, LevelChunk chunk,
            CallbackInfo ci) {
        if (!RuntimeServerContext.isShadowServerContext() || chunk == null) {
            return;
        }
        // 先物化：seedGen defer 分支会写 SeedGenCompareGate，再决定是否桥接
        hassium$notifyShadowMaterialized(chunk);
        // S3 接法 B：官方包桥接真实客户端（awaiting-compare 时 forward 内也会拒）
        Packet<?> packet = holder != null ? holder.getValue() : null;
        if (packet == null && lightEngine != null) {
            // 与 flush 序列化共用 chunkLock：否则 PalettedContainer ThreadingDetector
            //（pack 线程 vs 本钩子 extractChunkData，1.20.1 实测 s3flyrt）。
            final var engine = this.lightEngine;
            packet = io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.withChunkLock(
                    chunk.getPos(),
                    () -> (Packet<?>) new ClientboundLevelChunkWithLightPacket(
                            chunk, engine, null, null));
        }
        io.github.limuqy.mc.hassium.shadow.track.ShadowOfficialPacketBridge
                .forwardToRealClient(packet,
                        io.github.limuqy.mc.hassium.compat.LevelCompat
                                .getDimensionId(player.level()));
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
        if (!RuntimeServerContext.isShadowServerContext() || pos == null) {
            return;
        }
        // S2：禁止有盘/有注入 Imposter 同步短路。始终悬置 future + Provider 异步 acquire
        //（始终 compare/FULL）。SeedGen 门控开且 worldgen 允许时不压制，走原版生成链。
        if (ShadowChunkMapCompat.isWorldgenAllowed()
                || io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor.getInstance()
                        .isGenerationGateOpen()) {
            return;
        }
        String dimension = hassium$shadowDimension();
        // S3：光照缓存客户端口径——读盘完整光=命中，否则重算（不短路 future）。
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute
                .accountLightAtScheduleLoad(dimension, pos);
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> suspended =
                new CompletableFuture<>();
        ShadowChunkMapCompat.registerSuspendedLoad(dimension, pos, suspended);
        io.github.limuqy.mc.hassium.shadow.track.VanillaAlignedChunkProvider.getInstance()
                .acquire(dimension, pos,
                        io.github.limuqy.mc.hassium.shadow.track.ShadowChunkProvider.AcquireReason.TRACKING);
        cir.setReturnValue(suspended);
    }
#else
    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"), cancellable = true)
    private void hassium$shortCircuitInjectLoad(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!RuntimeServerContext.isShadowServerContext() || pos == null) {
            return;
        }
        if (ShadowChunkMapCompat.isWorldgenAllowed()
                || io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor.getInstance()
                        .isGenerationGateOpen()) {
            return;
        }
        String dimension = hassium$shadowDimension();
        // S3：光照缓存客户端口径——读盘完整光=命中，否则重算（不短路 future）。
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute
                .accountLightAtScheduleLoad(dimension, pos);
        CompletableFuture<ChunkAccess> suspended = new CompletableFuture<>();
        ShadowChunkMapCompat.registerSuspendedLoad(dimension, pos, suspended);
        io.github.limuqy.mc.hassium.shadow.track.VanillaAlignedChunkProvider.getInstance()
                .acquire(dimension, pos,
                        io.github.limuqy.mc.hassium.shadow.track.ShadowChunkProvider.AcquireReason.TRACKING);
        cir.setReturnValue(suspended);
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

    /** 影子区块物化桥：1.20.1={@code playerLoadedChunk}，1.21+={@code onChunkReadyToSend}。 */
    @Unique
    private void hassium$notifyShadowMaterialized(LevelChunk chunk) {
        if (!RuntimeServerContext.isShadowServerContext() || chunk == null) {
            return;
        }
        try {
            io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance()
                    .onChunkMaterialized(hassium$shadowDimension(), chunk.getPos(), chunk);
        } catch (Throwable t) {
            io.github.limuqy.mc.hassium.Constants.LOG.error(
                    "[SHADOW_TRACK] onChunkMaterialized failed ({}, {})",
                    chunk.getPos().x, chunk.getPos().z, t);
        }
    }

    /** 本 ChunkMap 所属维度（影子上下文）。
     *  优先 {@code this.level}；解析失败用 tracking 维，再失败才 OVERWORLD
     *  （禁止在 level 可解析时误回落主世界）。 */
    @Unique
    private String hassium$shadowDimension() {
        String dimension = LevelCompat.getDimensionId(this.level);
        if (dimension != null) {
            return dimension;
        }
        try {
            String tracked = io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession
                    .getInstance().currentDimension();
            if (tracked != null && !tracked.isEmpty()) {
                return tracked;
            }
        } catch (Throwable ignored) {
        }
        return io.github.limuqy.mc.hassium.utils.DimensionKey.OVERWORLD;
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
            io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace
                    .recordWorldgenStart(hassium$shadowDimension(), pos);
            return false;
        }
        if (io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor.getInstance()
                .isGenerationGateOpen()) {
            // 门控通过：虚拟玩家触发原版生成链（真实种子），产出经
            // playerLoadedChunk（1.20.1）/ onChunkReadyToSend（1.21+）桥转
            // 统一 Compare+Pull（生成内容作基线，服务端裁决）
            io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace
                    .recordWorldgenStart(hassium$shadowDimension(), pos);
            return false;
        }
        // S2：无数据柱悬置 + VanillaAlignedChunkProvider.acquire（scheduleChunkLoad 已处理）
        // 不再 onChunkSelected 旁路 pull
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
     * {@link io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute#withChunkLock}
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
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.lockChunk(chunk.getPos());
        hassium$saveLockDepth.set(hassium$saveLockDepth.get() + 1);
    }

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("RETURN"))
    private void hassium$unlockShadowSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        int depth = hassium$saveLockDepth.get();
        if (depth <= 0) {
            return;
        }
        hassium$saveLockDepth.set(depth - 1);
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.unlockChunk(chunk.getPos());
    }

}
