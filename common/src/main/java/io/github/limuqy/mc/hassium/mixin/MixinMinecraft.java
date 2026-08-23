package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 主类 Mixin：维度切换 / 断连时刷新缓存保存队列。
 * <p>
 * 配置由 ConfigSpec / 加载器事件管理，不再在此处读写 JSON。
 * <p>
 * T6：移除 L2 冻结/恢复窗口（客户端 failover 已退役）——setScreen 拦截、按键冻结防护、
 * setLevel 冻结复位、disconnect/clearLevel 冻结兜底全部删除；保留断连清理
 * （cleanupOnDisconnect HEAD）与最终清理（finalizeDisconnectIfTerminal TAIL）。
 * <p>
 * M3：重新引入 setScreen HEAD 拦截（仅网关登录失败接管——与原 T6 冻结拦截语义无关）：
 * 原版连接失败弹 DisconnectedScreen 时交 {@code NetworkCore.tryStartGatewayOnlyLogin}
 * 决策（store 命中 → 取消失败界面转仅网关登录）；同时实现 {@link MinecraftAccessor}
 * （pendingConnection 挂载，runTick 泵 tick）。
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft implements MinecraftAccessor {

    /** M3：仅网关登录挂载本地 Connection（runTick level==null 分支泵 tick）。 */
    @Accessor("pendingConnection")
    public abstract void hassium$setPendingConnection(net.minecraft.network.Connection connection);

    /**
     * M3 主连接失效恢复（仅网关登录）：原版连接失败（DisconnectedScreen）且当前屏为
     * ConnectScreen 时——已在仅网关登录中 → 通知会话收尾（登录期断开）并吞掉失败界面；
     * 否则 store 命中决策（tryStartGatewayOnlyLogin），命中则取消原版失败界面转仅网关登录。
     * <p>
     * 取消通知：ConnectScreen 在所有版本均无 onClose 覆写（init() 的 Cancel 按钮 →
     * setScreen(parent)，shouldCloseOnEsc=false），故在此拦截「离开 ConnectScreen 到
     * 父屏」——仅网关登录会话激活且目标 = 父屏时通知静默收尾（用户取消）。登录成功
     * handleLogin → setScreen(ReceivingLevelScreen) 非父屏，不取消。
     */
    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"), cancellable = true)
    private void hassium$onSetScreen(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        net.minecraft.client.gui.screens.Screen current = ((Minecraft) (Object) this).screen;
        // T0b 诊断：加载屏消退时刻（ReceivingLevelScreen 被替换为非加载屏，含 setScreen(null)）
#if MC_VER < MC_1_21_9
        if (current instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                && !(screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen)) {
#else
        if (current instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
                && !(screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)) {
#endif
            io.github.limuqy.mc.hassium.utils.LoginTiming.onLoadingScreenDismissed();
        }
        io.github.limuqy.mc.hassium.network.core.NetworkCore core =
                io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance();
        if (screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
            if (current instanceof net.minecraft.client.gui.screens.ConnectScreen) {
                if (core.isGatewayOnlyLogin()) {
                    core.notifyGatewayOnlyDisconnect();
                    ci.cancel();
                    return;
                }
                if (core.tryStartGatewayOnlyLogin((Minecraft) (Object) this)) {
                    ci.cancel();
                }
            }
            return;
        }
        if (current instanceof net.minecraft.client.gui.screens.ConnectScreen && core.isGatewayOnlyCancelTarget(screen)) {
            core.notifyGatewayOnlyCancel();
        }
    }

    /**
     * 断连缓存落盘（dump），在世界拆除之前触发——手动登出（PauseScreen 保存并退出 →
     * 主线程 {@code Minecraft.disconnect(Screen[,Z])} / {@code clearLevel}）时同步执行，
     * 解决「onDisconnect（Netty 线程）→ mc.execute 排队」晚于 {@code disconnect} TAIL
     * 的 {@code finalizeDisconnect}（dirty clearAll + storage close）→ 排队 dump 全被
     * dirty gate 挡住（queued=0，光照/方块不落盘）。
     * <p>
     * 被动断开（服务器踢/断网）不经过此入口，仍走 listener onDisconnect 注入
     * （Netty 线程 execute 排队先于 vanilla handleDisconnection，无此竞态）。
     */
#if MC_VER < MC_1_21_1
    /**
     * 影子注册表门（写侧）：{@code clearLevel(Screen)} 是 1.20.1 forge/neoforge 所有客户端
     * 断连路径的汇聚点，forge patch 在其中注入 {@code ForgeHooksClient.handleClientLevelClosing}
     * → 同步执行 {@code GameData.revertToFrozen()}（清空重灌 ACTIVE 注册表的 BiMap）。
     * 写锁覆盖本方法全程（含 revert），与影子端序列化路径的读锁
     * （{@link io.github.limuqy.mc.hassium.compat.ShadowRegistryGate#withReadAccess}）
     * 结构性互斥——write/read 永不落在重建窗口内，vanilla
     * {@code Unknown registry element} ERROR 行不再出现。
     * fabric 无重建机制：写锁无竞争方，零开销。
     */
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void hassium$registryGateAcquire(CallbackInfo ci) {
        io.github.limuqy.mc.hassium.compat.ShadowRegistryGate.acquireWrite();
    }

    /** 与 {@link #hassium$registryGateAcquire} 成对：世界拆除 + revert 完成后放行影子序列化。 */
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void hassium$registryGateRelease(CallbackInfo ci) {
        io.github.limuqy.mc.hassium.compat.ShadowRegistryGate.releaseWrite();
    }
#endif

#if MC_VER < MC_1_21_1
    @Inject(method = "clearLevel", at = @At("HEAD"))
    private void hassium$dumpCacheOnDisconnect(CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }
#elif MC_VER < MC_1_21_11
    @Inject(method = "disconnect()V", at = @At("HEAD"), require = 0)
    private void hassium$dumpCacheOnDisconnectNoScreen(CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void hassium$dumpCacheOnDisconnect(net.minecraft.client.gui.screens.Screen screen,
                                               boolean keepResourcePacks, CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }
#else
    // review-fix: T13-FixT7Mixin-2：1.21.11+ disconnect 核心实现为 3 参 disconnect(Screen,boolean,boolean)，
    // disconnectWithProgressScreen 直调 3 参版绕过 2 参注入；HEAD 在世界拆除前触发缓存落盘
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void hassium$dumpCacheOnDisconnect(net.minecraft.client.gui.screens.Screen screen,
                                               boolean keepResourcePacks, boolean bl, CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }
#endif

    /**
     * 断连最终清理（drain 残余 + shutdown），在世界拆除之后触发。
     * <p>
     * <ul>
     *   <li>1.20.1：{@code clearLevel}</li>
     *   <li>1.21.1–1.21.10：{@code disconnect(Screen, boolean)}；部分 NeoForge 仍保留 {@code clearLevel}（require=0）</li>
     *   <li>1.21.11+：{@code disconnect(Screen, boolean, boolean)}（disconnectWithProgressScreen 直调 3 参版）</li>
     * </ul>
     * 与各加载器 DISCONNECT / LoggingOut 延后到下一 tick 的 finalize 互为兜底（{@code AtomicBoolean} 幂等）。
     */
#if MC_VER < MC_1_21_1
    @Inject(method = "clearLevel", at = @At("TAIL"))
    private void hassium$onClearLevel(CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }
#elif MC_VER < MC_1_21_11
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("TAIL"))
    private void hassium$onDisconnect(net.minecraft.client.gui.screens.Screen screen, boolean keepResourcePacks,
                                      CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }

    @Inject(method = "clearLevel", at = @At("TAIL"), require = 0)
    private void hassium$onClearLevelCompat(CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }
#else
    // review-fix: T13-FixT7Mixin-2：1.21.11+ 3 参 disconnect TAIL——世界拆除后最终清理
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("TAIL"))
    private void hassium$onDisconnect(net.minecraft.client.gui.screens.Screen screen, boolean keepResourcePacks,
                                      boolean bl, CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }

    @Inject(method = "clearLevel", at = @At("TAIL"), require = 0)
    private void hassium$onClearLevelCompat(CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnectIfTerminal();
    }
#endif
}
