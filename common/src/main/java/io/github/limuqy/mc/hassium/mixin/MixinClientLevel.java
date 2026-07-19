package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.CacheSaveQueue;
import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
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
     * 区块卸载时（兜底路径）：
     * <ol>
     *   <li>主路径：Forget 在 {@link MixinClientPacketListener} 已 cancel drop，通常不会到这里</li>
     *   <li>若当前已是 renderOnly：不写盘（保留历史快照）</li>
     *   <li>否则先异步落盘真实区块（须在超视渲染标记之前，避免 isRenderOnly 短路）</li>
     *   <li>超视渲染：若仍应在环带内，同栈 apply renderOnly 填回 slot</li>
     *   <li>成功替换时勿清 renderOnly 标记</li>
     * </ol>
     */
    @Inject(method = "unload", at = @At("HEAD"))
    private void hassium$onUnload(LevelChunk chunk, CallbackInfo ci) {
        ChunkPos pos = chunk.getPos();
        boolean wasRenderOnly = hassium$renderOnlyChunks.contains(pos)
                || ViewDistanceExtensionService.getInstance().isRenderOnly(pos);

        // 真实区块：先排队落盘（此时 isRenderOnly 仍为 false）
        if (!wasRenderOnly) {
            hassium$enqueueCacheSave(chunk);
        } else {
            Constants.LOG.debug("Hassium: [CACHE SAVE] Skip unload for render-only chunk {}", pos);
        }

        boolean substituted = false;
        try {
            // 兜底：Forget 未拦截时（非服务端 Forget 的 drop）同栈填回
            substituted = ViewDistanceExtensionService.getInstance().trySubstituteOnUnload(chunk);
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: OVD unload substitute error for {}", pos, e);
        }

        // 成功替换时 apply 已 addRenderOnly；勿再摘掉
        if (!substituted) {
            hassium$renderOnlyChunks.remove(pos);
        }
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
