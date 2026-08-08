package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.server.ServerSmokeTest;
import io.github.limuqy.mc.hassium.utils.TickMonitor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Mixin to MinecraftServer
 * <p>
 * 在服务器关闭时清理 ServerChunkPushManager
 */
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    // tickTimes[tickCount % 100] 的最新槽索引（1.20.1~1.21.11 同名私有字段）
    @Shadow
    private int tickCount;

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void onServerTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // 刷新服务端主线程回调队列（每 tick 调用）
        MainThreadDispatcher.flushServer();
        // 按真实 tick 限流序列化区块 + 冲刷 ChunkHash 批次。
        // 仅专用服务器（dedicated）激活：影子端（客户端进程内的 MinecraftServer）
        // 不接网络、无玩家，推送管理器不得对影子端世界生效。
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (RuntimeServerContext.isDedicatedServerContext()) {
            ServerChunkPushManager.getInstance().onServerTick(server);
        }
        // 服务端冒烟测试：检测玩家退出后切换视距
        ServerSmokeTest.onServerTick(server);
        DataPlaneUdpServer.tick(System.currentTimeMillis());
        // mspt 采样（debug.dispatcherLogging 开启时每秒输出一行 [MSPT]）
        TickMonitor.sampleServerTick(server, tickCount);
    }

    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"))
    private void onServerInit(CallbackInfo ci) {
        // 服务器初始化时设置服务器实例（用于 Fabric 网络管理器）
        MinecraftServer server = (MinecraftServer) (Object) this;
        // 记录服务器类型：存储格式等仅专用服务器功能需要（单人/局域网 integrated server 不启用）
        RuntimeServerContext.setDedicatedServer(server.isDedicatedServer());
        try {
            Class<?> fabricNetworkManager = Class.forName("io.github.limuqy.mc.hassium.network.FabricNetworkManager");
            java.lang.reflect.Method setServer = fabricNetworkManager.getMethod("setServerInstance", MinecraftServer.class);
            setServer.invoke(null, server);
        } catch (Exception e) {
            // 忽略，非关键功能
        }
        // 初始化服务端冒烟测试（设置初始 VD=20）
        ServerSmokeTest.initIfEnabled(server);
        // 绑定 UDP 数据端口（Task 3 cutover：旧 PoC TCP DataPlaneServer 已退役为 façade）。
        // 绑定失败不得拖垮 vanilla TCP——主控/缓存路径仍可用；UDP 数据面与加权分流降级。
        try {
            DataPlaneUdpServer.bind();
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: Failed to bind UDP dataplane, server will run without it", t);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void onServerStop(CallbackInfo ci) {
        // 服务器关闭时清理推送管理器
        ServerChunkPushManager.getInstance().shutdown();
        Constants.LOG.info("Hassium: ServerChunkPushManager shutdown");
        // 关闭 UDP 数据端口
        DataPlaneUdpServer.shutdown();
        // 清理玩家压缩状态追踪
        PlayerCompressionTracker.clear();
        Constants.LOG.info("Hassium: PlayerCompressionTracker cleared");
    }
}
