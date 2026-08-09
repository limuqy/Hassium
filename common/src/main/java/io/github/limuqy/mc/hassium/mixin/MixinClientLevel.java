package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
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
     * L2 世界定格：恢复窗口内暂停世界 tick（区块加载/时间推进等），画面保持定格。
     * 仅在 {@code network.dataPlane.recoveryFreeze=true}（默认）时生效；false=无感切换：
     * 世界继续运行，玩家本地操作照常（C2S 被恢复窗口吞掉），恢复成功后 setLevel 回退。
     * 全版本生效（{@code ClientLevel.tick(BooleanSupplier)} 签名跨 1.20.1~1.21.11 稳定）。
     */
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeTick(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isRecoveryFreeze()
                && io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            ci.cancel();
        }
    }

    /**
     * L2 世界定格：恢复窗口内暂停实体 tick。
     */
    @Inject(method = "tickEntities", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeTickEntities(CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isRecoveryFreeze()
                && io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            ci.cancel();
        }
    }

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
     * 获取所有仅渲染区块
     */
    @Override
    public Set<Long> hassium$getRenderOnlyChunks() {
        return hassium$renderOnlyChunks;
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
        hassium$renderOnlyChunks.remove(pos.toLong());
    }

}
