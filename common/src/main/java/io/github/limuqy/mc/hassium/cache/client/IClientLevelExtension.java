package io.github.limuqy.mc.hassium.cache.client;

import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/**
 * ClientLevel 扩展接口
 * 用于访问 MixinClientLevel 添加的方法
 */
public interface IClientLevelExtension {

    /**
     * 获取仅渲染的区块集合
     */
    Set<ChunkPos> hassium$getRenderOnlyChunks();
}
