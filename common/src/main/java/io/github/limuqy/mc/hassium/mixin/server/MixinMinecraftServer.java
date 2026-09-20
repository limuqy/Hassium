package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.server.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.server.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.server.ServerHandshakeActivation;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.server.ServerSmokeTest;
import io.github.limuqy.mc.hassium.utils.TickMonitor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Mixin to MinecraftServer
 * <p>
 * 在服务器关闭时清理 ServerChunkPushManager；tick 泵聚合刷新 + 区块推送 + Play 激活。
 */
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    // tickTimes[tickCount % 100] 的最新槽索引（1.20.1~1.21.11 同名私有字段）
    @Shadow
    private int tickCount;

    /**
     * B4：影子 {@code ChunkMap} / 光照 mailbox 不挂全局 {@code Util.backgroundExecutor()}，
     * 避免和客户端 mesh 抢同一 ForkJoin 池。专用服构造走原版返回值。
     */
#if MC_VER < MC_1_21_2
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/Util;backgroundExecutor()Ljava/util/concurrent/ExecutorService;"))
    private java.util.concurrent.ExecutorService hassium$isolateShadowWorldgenExecutor() {
        return (java.util.concurrent.ExecutorService)
                io.github.limuqy.mc.hassium.compat.ShadowServerCompat.shadowWorldgenExecutor();
    }
#elif MC_VER < MC_1_21_11
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/Util;backgroundExecutor()Lnet/minecraft/TracingExecutor;"))
    private net.minecraft.TracingExecutor hassium$isolateShadowWorldgenExecutor() {
        return (net.minecraft.TracingExecutor)
                io.github.limuqy.mc.hassium.compat.ShadowServerCompat.shadowWorldgenExecutor();
    }
#else
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;backgroundExecutor()Lnet/minecraft/TracingExecutor;"))
    private net.minecraft.TracingExecutor hassium$isolateShadowWorldgenExecutor() {
        return (net.minecraft.TracingExecutor)
                io.github.limuqy.mc.hassium.compat.ShadowServerCompat.shadowWorldgenExecutor();
    }
#endif

    /**
     * tick 起点：翻实体密度页 + 重建降帧快照 + 用上一 tick 实测实体包数更新压力倍率。
     * 必须早于 {@code ChunkMap.tick()}（即早于本 tick 的任何实体发包）。
     */
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void hassium$onServerTickStart(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        io.github.limuqy.mc.hassium.server.entity.EntityUpdatePacing.onServerTickStart((MinecraftServer) (Object) this);
    }

    // review-fix: T7-59: handler 统一加 hassium$ 前缀（Mixin 惯例，避免与目标类未来同名成员 merge 冲突）
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void hassium$onServerTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // 刷新服务端主线程回调队列（每 tick 调用）
        long tFlush = System.nanoTime();
        MainThreadDispatcher.flushServer();
        TickMonitor.addHassiumFlushNs(System.nanoTime() - tFlush);
        // 按真实 tick 限流序列化区块 + 冲刷 ChunkHash 批次。
        // 仅专用服务器（dedicated）激活：影子端（客户端进程内的 MinecraftServer）
        // 不接网络、无玩家，推送管理器不得对影子端世界生效。
        MinecraftServer server = (MinecraftServer) (Object) this;
        boolean networkActive = io.github.limuqy.mc.hassium.server.ServerNetworkGate.isNetworkServerActive();
        if (networkActive) {
            ServerChunkPushManager.getInstance().onServerTick(server);
            // 登录协商结果 → Play 激活（connection 挂载后下发 play_init；空转零成本）
            ServerHandshakeActivation.drainPending(server);
            // tick 尾异步冲刷聚合缓冲（主线程只入队；主线程卡顿由 maxWait watchdog 兜底）
            io.github.limuqy.mc.hassium.protocol.HassiumAggregationManager.flushAllAsync();
        }
        // 服务端冒烟测试：检测玩家退出后切换视距
        ServerSmokeTest.onServerTick(server);
        // mspt 采样（debug.dispatcherLogging 开启时每秒输出一行 [MSPT]）
        TickMonitor.finishHassiumTick();
        TickMonitor.sampleServerTick(server, tickCount);
    }

    // review-fix: T7-59: handler 统一加 hassium$ 前缀
    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"))
    private void hassium$onServerInit(CallbackInfo ci) {
        // 服务器初始化时设置服务器实例（用于 Fabric 网络管理器）
        MinecraftServer server = (MinecraftServer) (Object) this;
        // 记录服务器类型：存储格式仅专用服；LAN 网络面另看 master.enabledOnLan + isPublished
        RuntimeServerContext.setDedicatedServer(server.isDedicatedServer());
        RuntimeServerContext.setActiveServer(server);
        try {
            Class<?> fabricNetworkManager = Class.forName("io.github.limuqy.mc.hassium.network.FabricNetworkManager");
            java.lang.reflect.Method setServer = fabricNetworkManager.getMethod("setServerInstance", MinecraftServer.class);
            setServer.invoke(null, server);
        } catch (Exception e) {
            // 忽略，非关键功能
        }
        // 初始化服务端冒烟测试（设置初始 VD=20）
        ServerSmokeTest.initIfEnabled(server);
    }

    // review-fix: T7-59: handler 统一加 hassium$ 前缀
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void hassium$onServerStop(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        // 服务器关闭时清理推送管理器
        ServerChunkPushManager.getInstance().shutdown();
        Constants.LOG.info("Hassium: ServerChunkPushManager shutdown");
        // 清理玩家压缩状态追踪
        PlayerCompressionTracker.clear();
        Constants.LOG.info("Hassium: PlayerCompressionTracker cleared");
        // 清理实体降帧引擎状态（密度索引 / 每连接实体包计数）
        io.github.limuqy.mc.hassium.server.entity.EntityUpdatePacing.clear();
        // 权威逐段 hash 缓存：键是 (维度, x, z)，**不得跨世界复用**（同维度同坐标换世界会撞）。
        // 关停前打一行统计：这是「缓存是否真被用起来」的唯一现成证据来源。
        Constants.LOG.info("Hassium: ChunkAuthorityHashes {}",
                io.github.limuqy.mc.hassium.server.ChunkAuthorityHashes.statsLine());
        io.github.limuqy.mc.hassium.server.ChunkAuthorityHashes.clear();
        RuntimeServerContext.setActiveServer(null);
    }
}
