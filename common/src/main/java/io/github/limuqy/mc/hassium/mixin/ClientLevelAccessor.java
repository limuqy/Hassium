package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ClientLevel 的 accessor 接口
 * 用于访问原版私有字段
 */
@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
public interface ClientLevelAccessor {

    /**
     * 获取客户端区块缓存
     */
    @Accessor("chunkSource")
    ClientChunkCache hassium$getChunkSource();
}
