package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.CacheSaveQueue;
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

        // 更新玩家坐标，用于 MainThreadDispatcher 距离优先级计算
        // 同时跟踪 ClientLevel，供断连时 bulk-enqueue（此时 mc.level 可能已 null）
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                MainThreadDispatcher.updatePlayerPosition(mc.player.getX(), mc.player.getZ());
            }
            if (mc.level != null) {
                CacheSaveQueue.getInstance().trackLevel(mc.level);
            }
        } catch (Exception e) {
            // 忽略
        }

        try {
            ViewDistanceExtensionService.getInstance().update();
        } catch (Exception e) {
            // 忽略更新错误
        }

        // storage 就绪门控：冲刷暂存 hash / 检查超时
        try {
            ClientMetadataHandler.tickPendingHashGate();
        } catch (Exception e) {
            // 忽略
        }

        // 主线程时间预算：网络回调 vs 缓存 apply 动态分配。
        // 两边都有活时保持 1/3:2/3，防止服务器推送饿死 readyQueue（虚空根因之一）；
        // 任一侧空闲时把整帧预算给另一侧，避免全局压缩后的纯推送路径被卡在 forceOne≈1/帧。
        long budgetNs = ClientMainThreadBudget.getBudgetNs();
        int hardCap = ClientMainThreadBudget.getHardCap();
        long frameStartNs = System.nanoTime();
        long frameDeadlineNs = frameStartNs + budgetNs;

        boolean hasFlush = MainThreadDispatcher.getClientQueueSize() > 0;
        boolean hasCache = ClientCacheLoadQueue.getInstance().getReadySize() > 0;

        try {
            if (hasFlush) {
                // 两侧都忙：flush 先吃 1/3；仅 flush 有活：给满整帧
                long flushDeadlineNs = hasCache
                        ? (frameStartNs + budgetNs / 3)
                        : frameDeadlineNs;
                MainThreadDispatcher.flushClientUntil(flushDeadlineNs, hardCap);
            }
        } catch (Exception e) {
            MainThreadDispatcher.flushClient();
        }

        try {
            // 缓存侧拿剩余墙钟预算（含 flush 提前结束时的回收）
            if (hasCache || ClientCacheLoadQueue.getInstance().getReadySize() > 0) {
                ClientCacheLoadQueue.getInstance().processQueueUntil(frameDeadlineNs);
            }
        } catch (Exception e) {
            // 忽略加载错误
        }
    }
}
