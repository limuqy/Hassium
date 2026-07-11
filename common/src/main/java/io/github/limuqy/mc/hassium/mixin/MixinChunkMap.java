package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to ChunkMap to track chunk save events
 * <p>
 * 预留用于未来可能需要的区块保存事件处理。
 * 当前时间戳管理已简化，直接从 LevelChunk.getInhabitedTime() 获取。
 */
@Mixin(ChunkMap.class)
public class MixinChunkMap {

    @Shadow
    @Final
    ServerLevel level;

    /**
     * 拦截 ChunkMap.save() 方法
     * <p>
     * 预留注入点，当前仅记录日志。
     * 区块时间戳直接从 LevelChunk 对象获取，无需额外存储。
     */
    @Inject(method = "save", at = @At("RETURN"))
    private void hassium$onChunkSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        // 预留：区块保存事件处理
        // inhabitedTime 已经包含在 ChunkAccess 对象中，无需额外存储
    }
}
