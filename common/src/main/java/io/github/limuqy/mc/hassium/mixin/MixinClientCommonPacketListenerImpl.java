package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.2+：{@code onDisconnect} 从 {@code ClientPacketListener} 上移到
 * {@code ClientCommonPacketListenerImpl}，在此注入断开清理。
 * <p>
 * 1.20.1 无 {@code ClientCommonPacketListenerImpl}，挂空壳到 {@code Minecraft}
 * 以满足 mixins.json 注册。
 * <p>
 * 清理逻辑在 {@link ClientLifecycleHelper#cleanupOnDisconnect()}（非 Mixin 类），
 * 因 Mixin 0.8.7 不允许 Mixin 类中存在非 private 的静态方法。
 */
#if MC_VER >= MC_1_20_2
@Mixin(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class)
#else
@Mixin(net.minecraft.client.Minecraft.class)
#endif
public class MixinClientCommonPacketListenerImpl {

#if MC_VER >= MC_1_20_2
    /**
     * 断开连接时清理（1.20.2+）
     * <p>
     * 1.20.2~1.20.6：{@code onDisconnect(Component)}
     * 1.21.1+：{@code onDisconnect(DisconnectionDetails)}
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hassium$onDisconnect(
#if MC_VER < MC_1_21_1
            net.minecraft.network.chat.Component reason,
#else
            net.minecraft.network.DisconnectionDetails details,
#endif
            CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }

    /**
     * L2 恢复窗口 begin / UDP keepLease（在冻结 cancel 之前声明执行）。
     * <p>
     * 与 1.20.1 的 MixinClientPacketListener.hassium$beginRecoveryState 同逻辑：
     * 无论先跑还是后跑（fabric DISCONNECT 事件同点竞争），恢复态 begin 与
     * stopUdp(keepLease=true) 都必须已就位（stopUdp keepLease 幂等，双调安全），
     * 否则恢复窗口内 finalize 不被抑制、UDP 束被硬关。
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hassium$beginRecoveryState(
#if MC_VER < MC_1_21_1
            net.minecraft.network.chat.Component reason,
#else
            net.minecraft.network.DisconnectionDetails details,
#endif
            CallbackInfo ci) {
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            return;
        }
        io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.getInstance().begin(
                java.lang.System.currentTimeMillis() + 60_000L);
        io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle.getInstance()
                .stopUdp(true);
    }

    /**
     * L2 世界定格：恢复窗口中取消 vanilla onDisconnect 方法体（mc.disconnect →
     * clearLevel + setScreen 均不执行），世界画面保持冻结；恢复成功 setLevel 或
     * terminal 回退后再放行。
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeOnDisconnect(
#if MC_VER < MC_1_21_1
            net.minecraft.network.chat.Component reason,
#else
            net.minecraft.network.DisconnectionDetails details,
#endif
            CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
    }

#endif
}
