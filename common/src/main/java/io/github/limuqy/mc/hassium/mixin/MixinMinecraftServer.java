package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
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

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void onServerTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // 刷新服务端主线程回调队列（每 tick 调用）
        MainThreadDispatcher.flushServer();
    }

    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"))
    private void onServerInit(CallbackInfo ci) {
        // 服务器初始化时设置服务器实例（用于 Fabric 网络管理器）
        MinecraftServer server = (MinecraftServer) (Object) this;
        try {
            Class<?> fabricNetworkManager = Class.forName("io.github.limuqy.mc.hassium.network.FabricNetworkManager");
            java.lang.reflect.Method setServer = fabricNetworkManager.getMethod("setServerInstance", MinecraftServer.class);
            setServer.invoke(null, server);
        } catch (Exception e) {
            // 忽略，非关键功能
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void onServerStop(CallbackInfo ci) {
        // 服务器关闭时清理推送管理器
        ServerChunkPushManager.getInstance().shutdown();
        Constants.LOG.info("Hassium: ServerChunkPushManager shutdown");
        // 清理玩家压缩状态追踪
        PlayerCompressionTracker.clear();
        Constants.LOG.info("Hassium: PlayerCompressionTracker cleared");
    }
}
