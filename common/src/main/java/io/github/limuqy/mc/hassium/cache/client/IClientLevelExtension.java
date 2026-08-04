package io.github.limuqy.mc.hassium.cache.client;

import java.util.Set;

/**
 * ClientLevel 扩展接口
 * 用于访问 MixinClientLevel 添加的方法
 */
public interface IClientLevelExtension {

    /**
     * 获取仅渲染的区块集合（long 键 = ChunkPos.asLong）
     */
    Set<Long> hassium$getRenderOnlyChunks();

    /**
     * 检查指定区块是否为仅渲染区块（超视渲染）
     */
    boolean hassium$isRenderOnly(long pos);

    /**
     * 添加仅渲染区块标记
     */
    void hassium$addRenderOnlyChunk(long pos);

    /**
     * 移除仅渲染区块标记
     */
    void hassium$removeRenderOnlyChunk(long pos);
}
