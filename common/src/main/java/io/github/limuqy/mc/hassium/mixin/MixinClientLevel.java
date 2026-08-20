package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
/**
 * 扩展客户端世界状态，支持区块缓存保存
 */
@Mixin(ClientLevel.class)
public class MixinClientLevel implements IClientLevelExtension {

    @Unique
    private final Set<Long> hassium$renderOnlyChunks = new HashSet<>();

    /**
     * 检查区块是否为仅渲染区块
     */
    @Override
    public boolean hassium$isRenderOnly(long pos) {
        return hassium$renderOnlyChunks.contains(pos);
    }

    /**
     * 添加仅渲染区块
     */
    @Override
    public void hassium$addRenderOnlyChunk(long pos) {
        hassium$renderOnlyChunks.add(pos);
        Constants.LOG.debug("Hassium: Added render-only chunk {}", pos);
    }

    /**
     * 移除仅渲染区块
     */
    @Override
    public void hassium$removeRenderOnlyChunk(long pos) {
        hassium$renderOnlyChunks.remove(pos);
    }

    /**
     * 获取所有仅渲染区块（防御性拷贝，外部不可修改内部簿记）
     */
    @Override
    public Set<Long> hassium$getRenderOnlyChunks() {
        // review-fix: T7-63: 原返回内部 HashSet 引用，外部可修改破坏 renderOnly 簿记；
        // 全库无调用方需可变视图（grep 核实仅接口声明 + 本实现），返回不可变副本
        return Collections.unmodifiableSet(new HashSet<>(hassium$renderOnlyChunks));
    }

    /**
     * 区块卸载时：
     * <p>
     * 新架构（客户端零侵入）：断连落盘由影子端 saveAll 统一承担；OVD 区块
     * 数据常驻影子端存档，卸载直接 drop（重进环带 OVD 读盘快速补）。
     * 仅维护 renderOnly 标记的摘除。
     */
    @Inject(method = "unload", at = @At("HEAD"))
    private void hassium$onUnload(LevelChunk chunk, CallbackInfo ci) {
        ChunkPos pos = chunk.getPos();
        boolean wasRenderOnly = hassium$renderOnlyChunks.remove(pos.toLong());
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.onClientChunkUnloaded(pos);
        hassium$logChunkUnload(pos, wasRenderOnly);
    }

    @Unique
    private void hassium$logChunkUnload(ChunkPos pos, boolean wasRenderOnly) {
        if (!DebugLogger.isEnabled(LogType.CHUNK_APPLY)) {
            return;
        }
        long eventMs = System.currentTimeMillis();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "[CHUNK_UNLOAD] eventMs={} target=({},{}) renderOnly={} player=unavailable",
                    eventMs, pos.x, pos.z, wasRenderOnly);
            return;
        }
        int playerX = (int) Math.floor(minecraft.player.getX());
        int playerY = (int) Math.floor(minecraft.player.getY());
        int playerZ = (int) Math.floor(minecraft.player.getZ());
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[CHUNK_UNLOAD] eventMs={} target=({},{}) renderOnly={} playerBlock=({},{},{}) playerChunk=({},{})",
                eventMs, pos.x, pos.z, wasRenderOnly, playerX, playerY, playerZ, playerX >> 4, playerZ >> 4);
    }

}
