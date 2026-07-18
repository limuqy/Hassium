package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientCacheLoadQueue;
import io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 Tick Mixin
 * 用于在每帧更新视距扩展服务、按时间预算应用区块与刷新回调。
 */
@Mixin(Minecraft.class)
public class MixinClientTick {

    /**
     * 在客户端 tick 中更新视距扩展和处理缓存加载队列
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hassium$onTick(CallbackInfo ci) {
        try {
            ViewDistanceExtensionService.getInstance().update();
        } catch (Exception e) {
            // 忽略更新错误
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

        // storage 就绪门控：冲刷暂存 hash / 检查超时
        try {
            ClientMetadataHandler.tickPendingHashGate();
        } catch (Exception e) {
            // 忽略
        }

        // 共享主线程时间预算：先 flush 近距网络回调，再 apply 缓存区块
        long deadlineNs = System.nanoTime() + ClientMainThreadBudget.getBudgetNs();
        int hardCap = ClientMainThreadBudget.getHardCap();

        try {
            MainThreadDispatcher.flushClientUntil(deadlineNs, hardCap);
        } catch (Exception e) {
            MainThreadDispatcher.flushClient();
        }

        try {
            ClientCacheLoadQueue.getInstance().processQueueUntil(deadlineNs);
        } catch (Exception e) {
            // 忽略加载错误
        }
    }
}
