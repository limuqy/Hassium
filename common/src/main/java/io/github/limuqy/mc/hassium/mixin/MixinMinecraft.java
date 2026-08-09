package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
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
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

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
