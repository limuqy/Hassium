package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.CacheSaveQueue;
import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
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
     * 在 level 切换前刷新缓存保存队列。
     * <p>
     * 断连路径上 {@link ClientLifecycleHelper#cleanupOnDisconnect()} 已在更早阶段
     * 批量 enqueue；此处仅 flush 残余任务（维度切换等路径也受益）。
     * <p>
     * 1.20.5–1.21.8：{@code setLevel(ClientLevel, ReceivingLevelScreen.Reason)}；
     * 1.21.9+：Reason 参数移除，恢复为单参数。
     */
#if MC_VER < MC_1_20_5
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
    }
#elif MC_VER < MC_1_21_9
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel,
                                     net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason reason,
                                     CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
    }
#else
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
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
        ClientLifecycleHelper.finalizeDisconnect();
    }
#elif MC_VER < MC_1_20_5
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void hassium$onDisconnect(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnect();
    }
#else
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("TAIL"))
    private void hassium$onDisconnect(net.minecraft.client.gui.screens.Screen screen, boolean keepResourcePacks,
                                      CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnect();
    }

    @Inject(method = "clearLevel", at = @At("TAIL"), require = 0)
    private void hassium$onClearLevelCompat(CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnect();
    }
#endif
}
