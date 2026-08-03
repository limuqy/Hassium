package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.client.ServerListBackupPing;
import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity;
import io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 服务器列表备用状态接管入口。全部实现位于 mixin 包外的 {@link ServerListBackupPing}：
 * {@code @Unique} 注入成员引用的类型若处于 mixin 包内（私有嵌套类/匿名类），forge 的
 * mixin 包保护会在目标类加载时抛 {@code IllegalClassLoadError}（fabric 不检查）。
 */
@Mixin(ServerStatusPinger.class)
public class MixinServerStatusPinger {

    /**
     * 拦截 vanilla pingServer：主地址（有持久化候选）直接改走候选 ping 链。
     * <p>
     * 1.20.1 的 {@code Connection.connectToServer} 是同步阻塞（syncUninterruptibly），
     * 主地址拒绝连接会同步抛异常 → ServerEntry 的 catch 直接显示红字，
     * {@code onPingFailed} 永不触发；因此接管点必须是 pingServer 本身。
     * 1.20.5+ 同入口（异步失败路径仍由 onPingFailed 注入兜底）。
     */
#if MC_VER < MC_1_20_5
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void hassium$onPingServer(ServerData serverData, Runnable refresh, CallbackInfo ci) {
#elif MC_VER < MC_1_21_11
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void hassium$onPingServer(ServerData serverData, Runnable iconRefresh, Runnable statusRefresh, CallbackInfo ci) {
#else
    // 1.21.11 的 pingServer 增加了 EventLoopGroupHolder 参数，mixin 描述符须对齐
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void hassium$onPingServer(ServerData serverData, Runnable iconRefresh, Runnable statusRefresh,
                                      net.minecraft.server.network.EventLoopGroupHolder eventLoopGroupHolder, CallbackInfo ci) {
#endif
        if (serverData == null || serverData.name.startsWith("hassium-failover:")) {
            return;
        }
        List<ControlEndpoint> candidates = ClientFailoverIdentity.findBackupFor(serverData.ip);
        if (candidates.isEmpty()) {
            return; // 普通服务器：vanilla 原样
        }
        ci.cancel();
        ServerListBackupPing.onPingServer(serverData);
    }

    @Inject(method = "onPingFailed", at = @At("HEAD"), cancellable = true)
    private void hassium$onPingFailed(Component reason, ServerData serverData, CallbackInfo ci) {
        if (serverData == null || serverData.name.startsWith("hassium-failover:")) {
            return; // 合成候选：不接管
        }
        List<ControlEndpoint> candidates = ClientFailoverIdentity.findBackupFor(serverData.ip);
        if (candidates.isEmpty()) {
            return; // 普通服务器：vanilla 原样
        }
        ci.cancel();
        ServerListBackupPing.onPingFailed(reason, serverData);
    }
}
