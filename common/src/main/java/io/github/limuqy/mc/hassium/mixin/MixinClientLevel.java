package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.CacheSaveQueue;
import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * 扩展客户端世界状态，支持区块缓存保存
 */
@Mixin(ClientLevel.class)
public class MixinClientLevel implements IClientLevelExtension {

    @Unique
    private final Set<ChunkPos> hassium$renderOnlyChunks = new HashSet<>();

    /**
     * 检查区块是否为仅渲染区块
     */
    @Override
    public boolean hassium$isRenderOnly(ChunkPos pos) {
        return hassium$renderOnlyChunks.contains(pos);
    }

    /**
     * 添加仅渲染区块
     */
    @Override
    public void hassium$addRenderOnlyChunk(ChunkPos pos) {
        hassium$renderOnlyChunks.add(pos);
        Constants.LOG.debug("Hassium: Added render-only chunk {}", pos);
    }

    /**
     * 移除仅渲染区块
     */
    @Override
    public void hassium$removeRenderOnlyChunk(ChunkPos pos) {
        hassium$renderOnlyChunks.remove(pos);
    }

    /**
     * 获取所有仅渲染区块
     */
    @Override
    public Set<ChunkPos> hassium$getRenderOnlyChunks() {
        return hassium$renderOnlyChunks;
    }

    /**
     * 区块卸载时提交缓存保存任务并清理 renderOnly 标记
     * <p>
     * 保存任务由 CacheSaveQueue 在后台线程异步执行，不阻塞主线程。
     */
    @Inject(method = "unload", at = @At("HEAD"))
    private void hassium$onUnload(LevelChunk chunk, CallbackInfo ci) {
        // 清理 renderOnly 标记
        hassium$renderOnlyChunks.remove(chunk.getPos());

        // 提交异步保存任务
        hassium$enqueueCacheSave(chunk);
    }

    /**
     * 将区块保存任务提交到异步队列
     */
    @Unique
    private void hassium$enqueueCacheSave(LevelChunk chunk) {
        try {
            // 检查缓存存储是否已初始化
            if (ClientChunkHandler.getClientStorage() == null) {
                return;
            }

            CacheSaveQueue.getInstance().enqueue(chunk);

        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to enqueue cache save for chunk {}",
                    chunk.getPos(), e);
        }
    }
}
