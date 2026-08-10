package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.PristineRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * pristine 跟踪的修改标记（SeedGen 前置，Phase 1）。
 * <p>
 * 登记表为空时零开销短路；任何 setBlockState 都视为对区块的修改，
 * 移除其 pristine 登记（服务端随后对该块不再发 SeedRef）。
 * <p>
 * 注入目标取 {@link LevelChunk#setBlockState}（两版本段均为具体方法）：
 * {@code ChunkAccess} 上的同名方法是抽象方法，Mixin 无法注入（asm insnNode null）。
 * 世界加载完成后的所有修改（玩家/插件/随机 tick）都经 LevelChunk 实现分派。
 */
@Mixin(LevelChunk.class)
public abstract class MixinChunkAccess {

    @Inject(method = "setBlockState"
            + "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
            // review-fix: T13-FixT7Mixin-1：1.21.2-1.21.4 第三参为 boolean，1.21.5+ 为 int；旧分界 <MC_1_21_2 会向 1.21.2-1.21.4 注入 I 描述符，启动即崩
#if MC_VER < MC_1_21_5
            + "Z"
#else
            + "I"
#endif
            + ")Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void hassium$onBlockModified(CallbackInfoReturnable<BlockState> cir) {
        if (PristineRegistry.isEmpty()) {
            return;
        }
        LevelChunk self = (LevelChunk) (Object) this;
        ChunkPos chunkPos = self.getPos();
        PristineRegistry.onBlockModified(self.getLevel().dimension(), new ChunkPos(chunkPos.x, chunkPos.z));
    }
}
