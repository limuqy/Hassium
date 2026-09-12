package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.network.ChunkAuthorityHashes;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 权威 chunkHash 的失效点：真实服务器世界任意方块变更即作废该柱缓存。
 * <p>
 * 背景：{@link ChunkAuthorityHashes} 在 enter 通知里被读取，命中即把 hash 直接附给客户端，
 * 客户端据此判定「权威内容 == 本地内容」并**零请求交付**。若方块变更后缓存未失效，客户端会
 * 拿到陈旧 hash、跳过 PULL，从而保留旧内容（静默内容错误）。当前冒烟恰好掩盖了该洞：enter
 * 瞬间客户端本地 hash 往往还没读盘（{@code ShadowStorageHashes.get} 返 null），于是仍走
 * 带基线比较的 PULL 兜底 —— 属于巧合，不是设计（classic 场景 R2 注入的石墙即该场景）。
 * <p>
 * 钩子选 {@code setBlockState} 的 RETURN（vanilla 全部方块变更的收口：玩家放置/破坏、方块
 * 实体 tick、结构/特征生成、爆炸等），返回 null 表示内容未变，跳过。
 * <p>
 * <b>描述符按段分叉</b>：1.21.5 起第三个参数由 {@code boolean isMoving} 改为 {@code int flags}
 * （{@code LevelChunk#setBlockState}），故以 {@code MC_1_21_5} 为界；两段共用同一实现体
 * {@code hassium$invalidateAuthorityHash0}。
 * <p>
 * 热路径保护：先查 {@link ChunkAuthorityHashes#hasEntries()}（volatile 免锁短路）——未协商
 * 权威位（原版客户端 / 单人 / 影子端）时缓存恒空，每次方块变更只多一次 volatile 读。影子端
 * 世界的内容变更与本缓存无关（影子端不消费权威 hash），显式跳过。
 */
@Mixin(LevelChunk.class)
public class MixinLevelChunk {

#if MC_VER < MC_1_21_5
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void hassium$invalidateAuthorityHash(BlockPos pos, BlockState state, boolean isMoving,
                                                 CallbackInfoReturnable<BlockState> cir) {
        hassium$invalidateAuthorityHash0(cir);
    }
#else
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void hassium$invalidateAuthorityHash(BlockPos pos, BlockState state, int flags,
                                                 CallbackInfoReturnable<BlockState> cir) {
        hassium$invalidateAuthorityHash0(cir);
    }
#endif

    @Unique
    private void hassium$invalidateAuthorityHash0(CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) {
            return; // 原版语义：返回 null = 该位置状态未变
        }
        if (!ChunkAuthorityHashes.hasEntries()) {
            return; // 权威缓存空闲：不触碰 level / 维度
        }
        LevelChunk self = (LevelChunk) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level) || RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        ChunkAuthorityHashes.invalidate(LevelCompat.getDimensionId(level), self.getPos());
    }
}
