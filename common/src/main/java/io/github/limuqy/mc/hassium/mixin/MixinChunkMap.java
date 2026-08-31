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
     */
    @ModifyVariable(method = "playerLoadedChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private MutableObject<ClientboundLevelChunkWithLightPacket> hassium$skipVanillaPacketBuild(
            MutableObject<ClientboundLevelChunkWithLightPacket> holder,
            ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> ignored,
            LevelChunk chunk) {
        if (RuntimeServerContext.isShadowServerContext()
                || player == null
                || !PlayerCompressionTracker.isCompressionEnabled(player)) {
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
        LevelChunk loaded = hassium$chunkForScheduleLoad(pos);
        if (loaded == null) {
            // 未命中不 cancel：空槽由 MixinRegionFile 返回 null → createEmpty + 透传，
            // 不得在这里 loadFromDisk（FULL 票邻柱会同步解压整圈）。
            return;
        }
        ImposterProtoChunk wrapped = ShadowChunkMapCompat.asImposter(loaded);
        cir.setReturnValue(CompletableFuture.completedFuture(Either.left(wrapped)));
    }

    // 1.20.5–1.20.6 的 ChunkResult/ChunkHolder 中间层注入已随版本支持裁剪删除（API 自 1.21.1 起变化）
#else
    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"), cancellable = true)
    private void hassium$shortCircuitInjectLoad(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LevelChunk loaded = hassium$chunkForScheduleLoad(pos);
        if (loaded != null) {
            cir.setReturnValue(ShadowChunkMapCompat.completedImposter(loaded));
        }
        // 未命中不 cancel：MixinRegionFile 对非 126 返回 null，避免 completedFuture(null) NPE。
    }

#endif

    @Unique
    private LevelChunk hassium$chunkForScheduleLoad(ChunkPos pos) {
        if (pos == null || !RuntimeServerContext.isShadowServerContext()) {
            return null;
        }
        ShadowSeedServer server = ShadowChunkMapCompat.shadowServerOrNull();
        if (server == null) {
            return null;
        }
        String dimension = LevelCompat.getDimensionId(this.level);
        if (dimension == null) {
            dimension = io.github.limuqy.mc.hassium.utils.DimensionKey.OVERWORLD;
        }
        LevelChunk injected = server.injectedChunk(dimension, pos.x, pos.z);
        if (injected != null) {
            return injected;
        }
        // 无 materialized 区块：不 cancel，让原版 IOWorker/type126 读盘或生成链决定下一步。
        return null;
    }

}
