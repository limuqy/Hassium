package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientCacheLoadQueue;
import io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
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
        // 开发冒烟：进服等待后打印 getClientStatsMessage 并退出（仅 -Dhassium.smokeTest=true）
        try {
            ClientSmokeTest.onClientTick(Minecraft.getInstance());
        } catch (Exception e) {
            // 冒烟失败不阻断正常 tick
        }

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

        // 主线程时间预算拆分：网络回调占 1/3，缓存 apply 占 2/3。
        // 避免 resyncTrackedChunks 等场景下服务器推送占满预算，导致 processQueueUntil 被饿死、
        // readyQueue 堆积与 reconcile 死循环（虚空根因之一）。
        long budgetNs = ClientMainThreadBudget.getBudgetNs();
        int hardCap = ClientMainThreadBudget.getHardCap();
        long flushBudgetNs = budgetNs / 3;
        long applyBudgetNs = budgetNs - flushBudgetNs;

        try {
            long flushDeadlineNs = System.nanoTime() + flushBudgetNs;
            MainThreadDispatcher.flushClientUntil(flushDeadlineNs, hardCap);
        } catch (Exception e) {
            MainThreadDispatcher.flushClient();
        }

        try {
            long applyDeadlineNs = System.nanoTime() + applyBudgetNs;
            ClientCacheLoadQueue.getInstance().processQueueUntil(applyDeadlineNs);
        } catch (Exception e) {
            // 忽略加载错误
        }
    }
}
