package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.CacheSaveQueue;
import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverAttemptMarker;
import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 主类 Mixin：维度切换 / 断连时刷新缓存保存队列。
 * <p>
 * 配置由 ConfigSpec / 加载器事件管理，不再在此处读写 JSON。
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/MixinMinecraft");

    /**
     * 初始失败 fallback 时让开屏幕槽位：vanilla startConnecting 开头拒绝
     * {@code screen instanceof ConnectScreen} 的二次连接（Attempt to connect while
     * already connecting），直接字段赋值避免 setScreen 触发 UI 闪烁。
     */
    @Shadow
    private Screen screen;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void hassium$onDisconnectedScreen(Screen screen, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()) {
            if (screen instanceof DisconnectedScreen) {
                io.github.limuqy.mc.hassium.Constants.LOG.info(
                        "[diag] Minecraft.setScreen target=DisconnectedScreen current={} marked={} recovering={}",
                        minecraft.screen == null ? "null" : minecraft.screen.getClass().getSimpleName(),
                        ClientFailoverAttemptMarker.isMarked(),
                        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering());
            }
        }
        // L2 定格：恢复窗口内过渡画面不绘制（渲染层由 MixinGameRenderer 隐藏），但 setScreen
        // 必须放行——Fabric ClientNetworkingImpl.getLoginConnection 依赖
        // {@code mc.screen instanceof ConnectScreen} 取回候选连接做 registerReceiver 检查，
        // 拦截显示会让候选连接在 Login 阶段被 IllegalStateException 杀死：
        //  - DisconnectedScreen：候选失败 vanilla 直设，会破坏定格 → 拦截 + 推进轮转
        //  - ConnectScreen / ProgressScreen / ReceivingLevelScreen：放行（显示状态完整，
        //    vanilla tick 驱动 ConnectScreen 推进 login；渲染被隐藏，画面保持定格世界 + HUD）
        // 全版本生效（≥1.20.2 候选失败路径同样由 vanilla 直设 DisconnectedScreen）。
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            if (screen instanceof DisconnectedScreen) {
                // 候选 TCP/DNS 失败（ConnectScreen.connect 异常 → vanilla 弹 DisconnectedScreen）：
                // launcher 的 onFailure 是 noop，推进必须在此完成，否则 current 悬挂、
                // 恢复窗口永远不结束（overlay 卡死）。onInitialTcpConnectionFailed 的
                // recovering 分支剔除 current 并 launch 下一候选，耗尽则 terminal。
                if (minecraft.screen instanceof ConnectScreen
                        && io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverAttemptMarker.isMarked()) {
                    io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity
                            .onInitialTcpConnectionFailed();
                }
                ci.cancel();
                return;
            }
        }
        if (!(screen instanceof DisconnectedScreen)
                || !(minecraft.screen instanceof ConnectScreen)
                || !ClientFailoverAttemptMarker.isMarked()) {
            if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()
                    && !(screen instanceof ConnectScreen)
                    && !(screen instanceof DisconnectedScreen)) {
                io.github.limuqy.mc.hassium.Constants.LOG.info(
                        "[diag] MixinMinecraft bypass setScreen({}) current={} marked={} (clear marker if not ConnectScreen)",
                        screen == null ? "null" : screen.getClass().getSimpleName(),
                        minecraft.screen == null ? "null" : minecraft.screen.getClass().getSimpleName(),
                        ClientFailoverAttemptMarker.isMarked());
            }
            // 连接流程中的过渡屏（ProgressScreen 等）不清 marker：vanilla startConnecting
            // 先切 ProgressScreen 再切 ConnectScreen，若在此清掉标记，主地址 TCP 失败弹
            // DisconnectedScreen 时拦截链因 marked=false 失效，初始 fallback 永不触发。
            if (!(screen instanceof ConnectScreen) && !(screen instanceof ProgressScreen)) {
                ClientFailoverAttemptMarker.clear();
            }
            return;
        }
        ConnectScreen connectScreen = (ConnectScreen) minecraft.screen;
        net.minecraft.network.Connection connection =
                ((ConnectScreenAccessor) connectScreen).hassium$getConnection();
        if (connection == null && ClientFailoverIdentity.onInitialTcpConnectionFailed()) {
            // 让开屏幕槽位：vanilla startConnecting 会拒绝 screen 仍为 ConnectScreen 的
            // 二次连接（"Attempt to connect while already connecting"）。直接字段赋值，
            // 不触发 setScreen（无 UI 闪烁）；launch 的合成连接下帧接管。
            this.screen = null;
            ci.cancel();
        } else {
            ClientFailoverAttemptMarker.clear();
        }
    }

    /**
     * 在 level 切换前刷新缓存保存队列。
     * <p>
     * 断连路径上 {@link ClientLifecycleHelper#cleanupOnDisconnect()} 已在更早阶段
     * 批量 enqueue；此处仅 flush 残余任务（维度切换等路径也受益）。
     * <p>
     * 1.20.5–1.21.8：{@code setLevel(ClientLevel, ReceivingLevelScreen.Reason)}；
     * 1.21.9+：Reason 参数移除，恢复为单参数。
     * <p>
     * L2 定格复位：新世界接管时清除冻结（恢复成功 setLevel / 正常新会话均放行）。
     */
#if MC_VER < MC_1_20_5
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
        // 无感切换恢复成功：新 level 即将接管，先把旧 level 内存区块快照入队（零磁盘 IO），
        // 消除恢复后区块重新加载的空窗；非恢复场景内部判定后直接返回。
        io.github.limuqy.mc.hassium.cache.client.RecoveryChunkPrefill.getInstance().captureAndStart(newLevel);
        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(false);
    }
#elif MC_VER < MC_1_21_9
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel,
                                     net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason reason,
                                     CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
        // 无感切换恢复成功：新 level 即将接管，先把旧 level 内存区块快照入队（零磁盘 IO），
        // 消除恢复后区块重新加载的空窗；非恢复场景内部判定后直接返回。
        io.github.limuqy.mc.hassium.cache.client.RecoveryChunkPrefill.getInstance().captureAndStart(newLevel);
        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(false);
    }
#else
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
        // 无感切换恢复成功：新 level 即将接管，先把旧 level 内存区块快照入队（零磁盘 IO），
        // 消除恢复后区块重新加载的空窗；非恢复场景内部判定后直接返回。
        io.github.limuqy.mc.hassium.cache.client.RecoveryChunkPrefill.getInstance().captureAndStart(newLevel);
        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(false);
    }
#endif

    @Unique
    private void hassium$flushCacheSaveQueue() {
        try {
            CacheSaveQueue.getInstance().flush();
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to flush cache save queue on level change", e);
        }
    }

    /**
     * L2 定格兜底：恢复窗口内取消世界拆除路径，保证世界不卸载。
     * <p>
     * 各段拆除入口：1.20.1 = {@code clearLevel}；1.20.2~1.21.5 = {@code Minecraft.disconnect()}
     * （launcher startConnecting 用，展示 ProgressScreen）；1.21.6+ = {@code disconnectWithProgressScreen()}
     * /（1.21.11）{@code disconnectWithProgressScreen(Z)}；listener 侧 {@code disconnect(Screen[,Z])}
     * 在恢复窗口内同样取消。此兜底仅当 listener 侧 onDisconnect 取消失效时触发，
     * 均以 {@code isRecovering()} 门控 —— TERMINAL 后恒 false，不再拦截。
     */
#if MC_VER < MC_1_20_2
    @Inject(method = "clearLevel", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeClearLevel(CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }
#elif MC_VER < MC_1_20_5
    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeDisconnectNoScreen(CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeDisconnectScreen(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }
#elif MC_VER < MC_1_21_6
    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeDisconnectNoScreen(CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeDisconnectScreen(net.minecraft.client.gui.screens.Screen screen,
                                                boolean keepResourcePacks, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }
#else
    @Inject(method = "disconnectWithProgressScreen", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeDisconnectWithProgress(CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }
#if MC_VER >= MC_1_21_11
    @Inject(method = "disconnectWithProgressScreen(Z)V", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeDisconnectWithProgressBool(boolean keepResourcePacks, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }
#endif
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeDisconnectScreen(net.minecraft.client.gui.screens.Screen screen,
                                                boolean keepResourcePacks, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }
#endif

    /**
     * 断连最终清理（drain 残余 + shutdown），在世界拆除之后触发。
     * <p>
     * <ul>
     *   <li>1.20.1：{@code clearLevel}</li>
     *   <li>1.20.2–1.20.4：{@code disconnect(Screen)}</li>
     *   <li>1.20.5+：{@code disconnect(Screen, boolean)}；部分 NeoForge 仍保留 {@code clearLevel}（require=0）</li>
     * </ul>
     * 与各加载器 DISCONNECT / LoggingOut 延后到下一 tick 的 finalize 互为兜底（{@code AtomicBoolean} 幂等）。
     */
#if MC_VER < MC_1_20_2
    @Inject(method = "clearLevel", at = @At("TAIL"))
    private void hassium$onClearLevel(CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }
#elif MC_VER < MC_1_20_5
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void hassium$onDisconnect(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }
#else
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("TAIL"))
    private void hassium$onDisconnect(net.minecraft.client.gui.screens.Screen screen, boolean keepResourcePacks,
                                      CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }

    @Inject(method = "clearLevel", at = @At("TAIL"), require = 0)
    private void hassium$onClearLevelCompat(CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }
#endif
}
