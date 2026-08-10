package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ResumeTicketValidator;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.ServerLoadReporter;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.server.ServerSmokeTest;
import io.github.limuqy.mc.hassium.server.GatewayPlatformWiring;
import io.github.limuqy.mc.hassium.server.GatewayPlayerBridge;
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

    // review-fix: T7-59: handler 统一加 hassium$ 前缀（Mixin 惯例，避免与目标类未来同名成员 merge 冲突）
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void hassium$onServerTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // 刷新服务端主线程回调队列（每 tick 调用）
        MainThreadDispatcher.flushServer();
        // 按真实 tick 限流序列化区块 + 冲刷 ChunkHash 批次。
        // 仅专用服务器（dedicated）激活：影子端（客户端进程内的 MinecraftServer）
        // 不接网络、无玩家，推送管理器不得对影子端世界生效。
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (RuntimeServerContext.isDedicatedServerContext()) {
            ServerChunkPushManager.getInstance().onServerTick(server);
            // T7 负载上报（REQ §B12）：周期采样 CPU/TPS/内存/玩家数，日志输出；
            // 网关侧接收口 TODO(T8)。
            ServerLoadReporter.onServerTick(server);
        }
        // 服务端冒烟测试：检测玩家退出后切换视距
        ServerSmokeTest.onServerTick(server);
        DataPlaneUdpServer.tick(System.currentTimeMillis());
        // T12 网关登录桥泵（登录监听器 tick + 物化检测 + 断连清理；空转零成本）
        GatewayPlayerBridge.tick(server);
        // mspt 采样（debug.dispatcherLogging 开启时每秒输出一行 [MSPT]）
        TickMonitor.sampleServerTick(server, tickCount);
        // T2 票据防重放：epoch 表定期落盘（内部 60s 限频；停机窗口重放由 5min 时间窗口兜底）
        ResumeTicketValidator.persistIfDue();
    }

    // review-fix: T7-59: handler 统一加 hassium$ 前缀
    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"))
    private void hassium$onServerInit(CallbackInfo ci) {
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
        // T12 网关接入（主控专用）：真实握手字段 + 登录桥 + 续流物化 + S2C 推送路由。
        // 失败仅日志（GatewayPlatformWiring 内部兜底）——vanilla TCP 不受影响。
        if (RuntimeServerContext.isDedicatedServerContext()) {
            GatewayPlatformWiring.install(server);
        }
        // T2 票据防重放：epoch 表启动加载 + 有效期配置（config 目录 hassium-state.json）。
        // 配置读取失败仅告警并回退默认 TTL——防重放持久化不阻断服务器启动。
        try {
            ResumeTicketValidator.configureStateFile(
                    Services.PLATFORM.getConfigDirectory().resolve("hassium-state.json"));
            ResumeTicketValidator.configureTtlMs(
                    HassiumConfigService.getInstance().getConfig().master().resumeTicketTtlMs());
            ResumeTicketValidator.load();
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: ResumeTicketValidator init failed (fallback defaults): {}", t.toString());
        }
    }

    // review-fix: T7-59: handler 统一加 hassium$ 前缀
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void hassium$onServerStop(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        // 服务器关闭时清理推送管理器
        ServerChunkPushManager.getInstance().shutdown();
        Constants.LOG.info("Hassium: ServerChunkPushManager shutdown");
        // 关闭 UDP 数据端口
        DataPlaneUdpServer.shutdown();
        // T12 网关停机（桥清理 + 会话完整清理；幂等）
        GatewayPlatformWiring.shutdown(server);
        // 清理玩家压缩状态追踪
        PlayerCompressionTracker.clear();
        Constants.LOG.info("Hassium: PlayerCompressionTracker cleared");
        // T2 票据防重放：epoch 表停机落盘（原子写；重启后 load 继续防重放）
        ResumeTicketValidator.save();
    }
}
