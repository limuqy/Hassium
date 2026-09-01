package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget;
import io.github.limuqy.mc.hassium.Constants;
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
        ClientMainThreadBudget.resetCacheReadBudget();
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
        // 无网关拓扑：进服后一次性发客户端握手（SeedGen 种子/LevelStem、剥光协商、位置上报）
        try {
            io.github.limuqy.mc.hassium.network.ClientHandshakeSender.tick(Minecraft.getInstance());
        } catch (Exception ignored) {
            // 握手失败不阻断正常 tick（服务端旧版无 HANDSHAKE 接收时自然忽略）
        }

        // 1.20.1 Forge revert 窗口内 pauseEncoding 只挡新的 ChunkSerializer。
        // 无世界时跳过 drain/OVD；窗口 TAIL 已 resume，标题画面仍可 tickStorageFlush。
        if (io.github.limuqy.mc.hassium.storage.ShadowStorageManager.isEncodingPaused()) {
            Minecraft pausedMc = Minecraft.getInstance();
            if (pausedMc == null || pausedMc.level == null || pausedMc.getConnection() == null) {
                return;
            }
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

        // Compare+Pull 在途拦截超时回退：响应未到则用已收网络数据注入（防区块黑洞）
        try {
            io.github.limuqy.mc.hassium.network.ShadowPullClient.expirePending();
        } catch (Exception e) {
            // 超时回退失败不阻断 tick
        }

        // （ready / pending / 在途光）时预留一半给 drainReady，避免 dispatcher 先把
        // deadline 用尽导致整帧 0 chunk（ROUND1 在 JoinBoost 10s 到期后曾因此卡 22s）。
        long budgetNs = ClientMainThreadBudget.getBudgetNs();
        long frameStartNs = System.nanoTime();
        boolean reserveDrainReady = ClientMainThreadBudget.isJoinBoostActive()
                || io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.hasBacklog();
        io.github.limuqy.mc.hassium.utils.StallDiag.noteJoinBoost(ClientMainThreadBudget.isJoinBoostActive());
        long dispatcherDeadlineNs = frameStartNs
                + ClientMainThreadBudget.dispatcherShareNs(budgetNs, reserveDrainReady);
        long frameDeadlineNs = frameStartNs + budgetNs;

        boolean hasFlush = MainThreadDispatcher.getClientQueueSize() > 0;

        try {
            if (hasFlush) {
                MainThreadDispatcher.flushClientUntil(dispatcherDeadlineNs);
            }
        } catch (Exception e) {
            MainThreadDispatcher.flushClientUntil(dispatcherDeadlineNs);
        }


        // 首登 SEED_REF/chunkHash 缓冲重放：login bridge 完成后服务端早于客户端 world
        // 就绪发包，world 就绪后每 tick 重放（登录过渡窗口内帧不丢，seedgen 不哑火）
        try {
            io.github.limuqy.mc.hassium.network.ClientMetadataHandler.drainPendingOnWorldReady();
        } catch (Exception e) {
            // 忽略
        }


        // 影子端缓存清理节流检查（容量/热度淘汰；超限时后台执行，不卡帧）
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowCacheEviction.tick();
        } catch (Exception e) {
            // 忽略
        }

        // 缓存存储定时刷新：后台从 ChunkMap 刷脏，不堵 tick。park 保活期间不刷，
        // 避免标题画面 / 重连投机把刚落盘的实例再拉进编码。
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry registry =
                    io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance();
            io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer shadow = registry.get();
            if (shadow != null && !registry.isParked()) {
                shadow.tickStorageFlush();
            }
        } catch (Exception e) {
            // 忽略
        }

        // 影子光照回传落地：帧尾渲染前，影子端（启用态）算好的光统一落地，
        // 黑块窗口 = 0（apply 后立即落地）。随后单柱失败兜底（注入失败/超时柱走
        // 客户端重算；正常流程不触发）。
        try {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.drainReady(frameDeadlineNs);
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
