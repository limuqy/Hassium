package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 Tick Mixin
 * 用于在每帧更新视距扩展服务
 */
@Mixin(Minecraft.class)
public class MixinClientTick {

    @Unique
    private static final ViewDistanceExtensionService hassium$viewDistanceService = new ViewDistanceExtensionService();

    /**
     * 获取视距扩展服务实例
     */
    @Unique
    private static ViewDistanceExtensionService hassium$getViewDistanceService() {
        return hassium$viewDistanceService;
    }

    /**
     * 在客户端 tick 中更新视距扩展和处理缓存加载队列
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hassium$onTick(CallbackInfo ci) {
        try {
            hassium$viewDistanceService.update();
        } catch (Exception e) {
            // 忽略更新错误
        }

        // 处理缓存加载队列（每帧最多加载10个区块）
        try {
            io.github.limuqy.mc.hassium.cache.client.ClientCacheLoadQueue.getInstance().processQueue();
        } catch (Exception e) {
            // 忽略加载错误
        }

        // 更新玩家坐标，用于 MainThreadDispatcher 距离优先级计算
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                MainThreadDispatcher.updatePlayerPosition(mc.player.getX(), mc.player.getZ());
            }
        } catch (Exception e) {
            // 忽略
        }

        // 刷新后台任务回调队列（按优先级、带单帧上限）
        try {
            int maxPerFrame = io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                    .getMaxCallbacksPerFrame();
            MainThreadDispatcher.flushClient(maxPerFrame);
        } catch (Exception e) {
            MainThreadDispatcher.flushClient(); // fallback to default
        }
    }
}
