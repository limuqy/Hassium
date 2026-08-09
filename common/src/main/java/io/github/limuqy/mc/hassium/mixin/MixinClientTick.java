package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import io.github.limuqy.mc.hassium.utils.TickMonitor;
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
     * mspt 采样：Minecraft.tick() HEAD 记录本 tick 起始。
     * 对应 TAIL 的 hassium$tickEnd 结算（声明在文件末尾，最后执行，覆盖全部本 tick 工作）。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void hassium$tickStart(CallbackInfo ci) {
        TickMonitor.beginClientTick();
    }

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
            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle.getInstance()
                    .tick(System.currentTimeMillis());
        } catch (Exception e) {
            // 数据面可选；时钟故障不得中断客户端 tick。
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                var lifecycle = io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle.getInstance();
                var pending = lifecycle.takePendingUdpStart();
                if (pending != null) {
                    lifecycle.startUdp(mc.player.getUUID(), pending.connectionEpoch(), pending);
                }
            }
        } catch (Exception e) {
            // UDP 数据面可选；延迟启动失败不得中断客户端 tick。
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

        try {
            ViewDistanceExtensionService.getInstance().update();
        } catch (Exception e) {
            // 忽略更新错误
        }

        // 主线程时间预算：网络回调 vs 影子光落地动态分配。
        long budgetNs = ClientMainThreadBudget.getBudgetNs();
        int hardCap = ClientMainThreadBudget.getHardCap();
        long frameStartNs = System.nanoTime();
        long frameDeadlineNs = frameStartNs + budgetNs;

        boolean hasFlush = MainThreadDispatcher.getClientQueueSize() > 0;

        try {
            if (hasFlush) {
                MainThreadDispatcher.flushClientUntil(frameDeadlineNs, hardCap);
            }
        } catch (Exception e) {
            MainThreadDispatcher.flushClient();
        }

        // 全量请求超时重发（fallback 链兜底；SeedGen 影子端接管时无请求）
        try {
            io.github.limuqy.mc.hassium.network.ClientMetadataHandler.tickPendingFullRequestTimeouts();
        } catch (Exception e) {
            // 忽略
        }

        // 分段增量请求超时回退全量（服务端始终回包，仅丢包/断连竞态兜底）
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.tickPendingDeltaTimeouts();
        } catch (Exception e) {
            // 忽略
        }

        // 影子端缓存清理节流检查（容量/热度淘汰；超限时后台执行，不卡帧）
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowCacheEviction.tick();
        } catch (Exception e) {
            // 忽略
        }

        // 影子光照回传落地：帧尾渲染前，影子端（启用态）算好的光统一落地，
        // 黑块窗口 = 0（apply 后立即落地）。随后单柱失败兜底（注入失败/超时柱走
        // 客户端重算；正常流程不触发）。
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.drainReady();
        } catch (Exception e) {
            // 影子光照可选；异常不得中断客户端 tick
        }
    }

    /**
     * mspt 采样结算：声明在文件末尾，作为最后一个 TAIL handler 执行，
     * 覆盖本 tick 全部工作（含上方 apply 预算/光照/调度回调）。
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hassium$tickEnd(CallbackInfo ci) {
        TickMonitor.endClientTick();
    }
}
