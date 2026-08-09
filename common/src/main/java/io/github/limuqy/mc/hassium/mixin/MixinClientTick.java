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
        // 渲染帧粒度重置缓存 apply 配额（readyQueue 消费 + OVD substitute 共用 maxChunksPerFrame）
        ClientMainThreadBudget.beginFrameCacheBudget();
    }

    /**
     * L2 定格终态回退：候选全部失败（phase==TERMINAL）时解除定格并回到断开画面。
     * <p>
     * 必须用 {@code phase()==TERMINAL} 精确限定：成功路径 onHandshakeAccepted 先置
     * recovering=false 再 setLevel，若仅看 !isRecovering() 会把成功的短暂窗口误判为终态。
     * <p>
     * 拆除入口各段不同：1.20.1 = {@code clearLevel()+setScreen}（该签名在 1.20.1 断连路径不可靠）；
     * 1.20.2~1.20.4 = {@code Minecraft.disconnect(Screen)}；≥1.20.5 = {@code disconnect(Screen,false)}。
     * 此刻 {@code isRecovering()==false}（TERMINAL），MixinMinecraft 的冻结 cancel 不会拦截。
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hassium$onTickTerminalUnfreeze(CallbackInfo ci) {
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isFreezeActive()
                || io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()
                || io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.getInstance().phase()
                        != io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.Phase.TERMINAL) {
            return;
        }
        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(false);
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.screens.DisconnectedScreen screen = new net.minecraft.client.gui.screens.DisconnectedScreen(
                mc.screen,
                net.minecraft.network.chat.Component.translatable("disconnect.lost"),
                net.minecraft.network.chat.Component.literal("主控切换失败，已断开连接"));
#if MC_VER < MC_1_20_2
        // 1.20.1 用 clearLevel + setScreen 而非 disconnect(Screen)（该签名在 1.20.1 断连路径不可靠）
        mc.clearLevel();
        mc.setScreen(screen);
#elif MC_VER < MC_1_20_5
        mc.disconnect(screen);
#else
        mc.disconnect(screen, false);
#endif
    }

    /**
     * L2 恢复会话渲染遮挡终结：新世界接管完成（候选连接 handleLogin 的 ReceivingLevelScreen
     * 已被 vanilla 移除 → screen==null）且恢复已成功（!isRecovering）后清除 session 标记，
     * 后续画面渲染恢复正常。
     * <p>
     * vanilla Minecraft.tick 仅在 {@code level==null} 时驱动 pendingConnection；恢复期间
     * ConnectScreen 已显示（setScreen 放行），由 vanilla tick 驱动，无需手动驱动。
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hassium$clearRecoverySession(CallbackInfo ci) {
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecoverySessionActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()
                && mc.screen == null && mc.level != null && mc.player != null) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markRecoverySession(false);
        }
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
                    if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()) {
                        io.github.limuqy.mc.hassium.Constants.LOG.info(
                                "[diag] MixinClientTick pendingUdpStart epoch={} recovering={} phase={}",
                                pending.connectionEpoch(),
                                io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.getInstance().isRecovering(),
                                io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.getInstance().phase());
                    }
                    // 延迟续接点必须补齐 onHandshakeAccepted + notifyFallback —— 否则
                    // player==null 握手延迟的场景会跳过 failover 身份确认与缓存身份映射。
                    try {
                        if (io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.getInstance().isRecovering()) {
                            io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.getInstance().markRecovered();
                            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.onPrimaryHandshakeAccepted(null);
                        } else {
                            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.onHandshakeAccepted();
                }
                        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.consumeSuccessfulFallback()
                                .ifPresent(endpoint -> mc.gui.getChat().addMessage(
                                        net.minecraft.network.chat.Component.literal("[Hassium] 主地址 "
                                                + io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.primaryAddress()
                                                + " 不可用，已通过备用端点 " + endpoint.host() + ":" + endpoint.port()
                                                + " 连接；服务器列表地址和缓存身份仍为主地址。")));
                    } catch (Throwable ignored) {}
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
