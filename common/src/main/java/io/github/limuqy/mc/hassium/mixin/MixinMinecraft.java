package io.github.limuqy.mc.hassium.mixin;

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
        //    vanilla tick 驱动 ConnectScreen 推进 login；渲染被隐藏，画面保持冻结世界 + HUD）
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
                // 定格标志补充置位：freezeDisconnect*/freezeOnDisconnect 只在拆除入口被拦时
                // markFreezeActive；forge LoggingOut defer 恢复启动与 onDisconnect 拆除重叠的
                // 竞态下拆除已开始（player 已置 null），此处拦截 DisconnectedScreen 显示时补置位，
                // 供 handleKeybinds 冻结防护（hassium$skipKeybindsWhileFrozen）识别窗口。
                io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
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
     * L2 冻结/恢复窗口按键防护：恢复窗口内 vanilla 断连拆除可能已把 player 置 null
     * （forge LoggingOut 的 200ms defer 恢复启动与 Render thread onDisconnect 拆除重叠的
     * 竞态窗口），而恢复窗口的 setScreen 拦截又取消了 DisconnectedScreen 显示（screen 保持
     * null）→ vanilla tick 在 {@code screen==null && player==null} 下仍会调 handleKeybinds
     * → NPE（1.21.1 forge UdpFailover 冒烟崩溃）。冻结窗口内跳过按键处理，其余 tick 继续
     * 驱动渲染/网络；正常游戏 / 无感恢复（player 非 null）与普通断连（screen 接管）不受影响。
     * <p>
     * 条件与 freeze 状态联动：freezeActive（拆除入口被拦）或 isRecovering（竞态窗口）期间
     * 且 player 已拆除才跳过；恢复成功后（markFreezeActive(false) / recovering=false）恢复
     * 正常按键处理。全版本签名一致（{@code private void handleKeybinds()}），无需分段。
     */
    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void hassium$skipKeybindsWhileFrozen(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.player == null
                && (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isFreezeActive()
                        || io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering())) {
            ci.cancel();
        }
    }

    /**
     * setLevel 不做任何缓存 flush：新架构下断连落盘由影子端 saveAll 统一承担
     * （SeedGenLevelCompat.shutdown），客户端无磁盘缓存队列；维度切换时影子端
     * 不重建，无需处理。
     * <p>
     * 1.20.5–1.21.8：{@code setLevel(ClientLevel, ReceivingLevelScreen.Reason)}；
     * 1.21.9+：Reason 参数移除，恢复为单参数。
     * <p>
     * L2 定格复位：新世界接管时清除冻结（恢复成功 setLevel / 正常新会话均放行）。
     */
#if MC_VER < MC_1_20_5
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        // 方案 A：无客户端磁盘缓存，无需快照预填充；恢复期区块由重连正常链重建。
        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(false);
    }
#elif MC_VER < MC_1_21_9
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel,
                                     net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason reason,
                                     CallbackInfo ci) {
        // 方案 A：无客户端磁盘缓存，无需快照预填充；恢复期区块由重连正常链重建。
        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(false);
    }
#else
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        // 方案 A：无客户端磁盘缓存，无需快照预填充；恢复期区块由重连正常链重建。
        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(false);
    }
#endif

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
     * 断连缓存落盘（dump），在世界拆除之前触发——手动登出（PauseScreen 保存并退出 →
     * 主线程 {@code Minecraft.disconnect(Screen[,Z])} / {@code clearLevel}）时同步执行，
     * 解决「onDisconnect（Netty 线程）→ mc.execute 排队」晚于 {@code disconnect} TAIL
     * 的 {@code finalizeDisconnect}（dirty clearAll + storage close）→ 排队 dump 全被
     * dirty gate 挡住（queued=0，光照/方块不落盘）。
     * <p>
     * 声明在 freeze 注入之后：恢复窗口内 freeze cancel 方法体 → 本注入一并跳过。
     * 被动断开（服务器踢/断网）不经过此入口，仍走 listener onDisconnect 注入
     * （Netty 线程 execute 排队先于 vanilla handleDisconnection，无此竞态）。
     */
#if MC_VER < MC_1_20_2
    @Inject(method = "clearLevel", at = @At("HEAD"))
    private void hassium$dumpCacheOnDisconnect(CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }
#elif MC_VER < MC_1_20_5
    @Inject(method = "disconnect()V", at = @At("HEAD"), require = 0)
    private void hassium$dumpCacheOnDisconnectNoScreen(CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void hassium$dumpCacheOnDisconnect(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }
#else
    @Inject(method = "disconnect()V", at = @At("HEAD"), require = 0)
    private void hassium$dumpCacheOnDisconnectNoScreen(CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void hassium$dumpCacheOnDisconnect(net.minecraft.client.gui.screens.Screen screen,
                                               boolean keepResourcePacks, CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
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
