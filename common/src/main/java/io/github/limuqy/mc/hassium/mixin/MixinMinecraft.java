package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
 * M3 网关登录拦截已随网络核心裁剪移除；setScreen 仅保留加载屏消退诊断。
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    /**
     * T0b 诊断：加载屏消退时刻（ReceivingLevelScreen 被替换为非加载屏，含 setScreen(null)）。
     */
    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void hassium$onSetScreen(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        net.minecraft.client.gui.screens.Screen current = ((Minecraft) (Object) this).screen;
        if (current instanceof net.minecraft.client.gui.screens.ConnectScreen) {
            ClientLifecycleHelper.onConnectScreenDismissed(screen);
        }
#if MC_VER < MC_1_21_9
        if (current instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                && !(screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen)) {
#else
        if (current instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
                && !(screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)) {
#endif
            io.github.limuqy.mc.hassium.utils.LoginTiming.onLoadingScreenDismissed();
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
    /** 与 {@link #hassium$registryGateAcquire} 成对：仅当 HEAD 真正取到写锁时 TAIL 才释放。 */
    @Unique
    private boolean hassium$registryWriteHeld;

    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void hassium$pauseShadowEncode(CallbackInfo ci) {
        // 仅挡住 revert 窗口内的新 ChunkSerializer，避免饿死注册表写锁。
        // ConnectScreen 空 clearLevel 不是会话拆除：pause 会卡住 drainReady。
        if (!ClientLifecycleHelper.hasActiveClientSession()) {
            return;
        }
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager.pauseEncoding();
    }

    /**
     * 影子注册表门（写侧）：{@code clearLevel(Screen)} 是 1.20.1 forge/neoforge 所有客户端
     * 断连路径的汇聚点，forge patch 在其中注入 {@code ForgeHooksClient.handleClientLevelClosing}
     * → 同步执行 {@code GameData.revertToFrozen()}（清空重灌 ACTIVE 注册表的 BiMap）。
     * 写锁覆盖本方法全程（含 revert），与影子端序列化路径的读锁
     * （{@link io.github.limuqy.mc.hassium.compat.ShadowRegistryGate#withReadAccess}）
     * 结构性互斥——write/read 永不落在重建窗口内，vanilla
     * {@code Unknown registry element} ERROR 行不再出现。
     * fabric 无重建机制：写锁无竞争方，零开销。
     * 须在 {@link #hassium$pauseShadowEncode} 之后取写锁，避免新编码再抢读锁。
     * 无会话的 connect 空 clearLevel 不取写锁（无 revert），以免堵住投机 WorldLoader。
     */
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void hassium$registryGateAcquire(CallbackInfo ci) {
        hassium$registryWriteHeld = false;
        if (!io.github.limuqy.mc.hassium.compat.ShadowRegistryGate.shouldHoldWriteLockDuringClearLevel()) {
            return;
        }
        if (!ClientLifecycleHelper.hasActiveClientSession()) {
            return;
        }
        io.github.limuqy.mc.hassium.compat.ShadowRegistryGate.acquireWrite();
        hassium$registryWriteHeld = true;
    }

    /** 与 {@link #hassium$registryGateAcquire} 成对：世界拆除 + revert 完成后放行影子序列化。 */
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void hassium$registryGateRelease(CallbackInfo ci) {
        if (hassium$registryWriteHeld) {
            hassium$registryWriteHeld = false;
            io.github.limuqy.mc.hassium.compat.ShadowRegistryGate.releaseWrite();
        }
        // revert 窗口已过：放行从还活着的影子 ChunkMap 刷脏（park 在 finalize TAIL）。
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager.resumeEncoding();
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
