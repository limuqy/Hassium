package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.platform.client.TraceOrigin;

import io.github.limuqy.mc.hassium.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientMetadataHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端数据包监听器
 * <p>
 * 负责初始化缓存系统和断开连接时清理。
 * 元数据处理逻辑在 {@link io.github.limuqy.mc.hassium.client.ClientMetadataHandler} 中。
 * <p>
 * 1.20.2+：{@code onDisconnect} 已上移到 {@code ClientCommonPacketListenerImpl}，
 * 由 {@link MixinClientCommonPacketListenerImpl} 注入。
 * <p>
 * 区块生命周期不在客户端侧维护：真实客户端仅应用影子端通过 vanilla 通道发送的
 * chunk+light / forget packet，并执行原版缓存与渲染。
 * <p>
 * 共享的清理 / 初始化逻辑已移至 {@link ClientLifecycleHelper}（非 Mixin 类），
 * 因 Mixin 0.8.7 不允许 Mixin 类中存在非 private 的静态方法。
 */
@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public class MixinClientPacketListener {
    /** handleLevelChunkWithLight 已消费 buffer 后不能再取 payload 长度，HEAD 暂存到同一监听器实例。 */
    @org.spongepowered.asm.mixin.Unique
    private boolean hassium$recordNativeChunk;
    @org.spongepowered.asm.mixin.Unique
    private int hassium$nativeChunkX;
    @org.spongepowered.asm.mixin.Unique
    private int hassium$nativeChunkZ;
    @org.spongepowered.asm.mixin.Unique
    private int hassium$nativeChunkPayloadBytes;

    /**
     * 自定义 Shadow/压缩回放也会调用原版 handler；该路径已由其唯一收口记账，不能重复计入 native。
     */
    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"), cancellable = true)
    private void hassium$captureNativeChunkMetric(
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet,
            CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.protocol.ShadowPullClient.handleNativeChunk(packet)) {
            this.hassium$recordNativeChunk = false;
            ci.cancel();
            return;
        }
        if (!io.github.limuqy.mc.hassium.client.ClientChunkPipeline.getInstance().isApplyInProgress()
                && io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.shouldInterceptVanillaChunks()) {
            // pull FULL 响应落地（影子 tracking 采集）以 REMOTE_PULL 归因，区别于服务端自主推送
            io.github.limuqy.mc.hassium.platform.client.TraceOrigin origin =
                    io.github.limuqy.mc.hassium.client.ClientChunkHandler.consumePullApplyOrigin(
                            packet.getX(), packet.getZ())
                            ? io.github.limuqy.mc.hassium.platform.client.TraceOrigin.REMOTE_PULL
                            : io.github.limuqy.mc.hassium.platform.client.TraceOrigin.SERVER_PUSH;
            io.github.limuqy.mc.hassium.shadow.light.ShadowVanillaLightPipeline.submitVisible(
                    io.github.limuqy.mc.hassium.shadow.light.ShadowVanillaLightPipeline.currentDimension(),
                    new net.minecraft.world.level.ChunkPos(packet.getX(), packet.getZ()), packet,
                    origin);
            this.hassium$recordNativeChunk = false;
            ci.cancel();
            return;
        }
        this.hassium$recordNativeChunk = !io.github.limuqy.mc.hassium.client.ClientChunkPipeline
                .getInstance().isApplyInProgress();
        if (!this.hassium$recordNativeChunk) {
            return;
        }
        this.hassium$nativeChunkX = packet.getX();
        this.hassium$nativeChunkZ = packet.getZ();
        this.hassium$nativeChunkPayloadBytes = packet.getChunkData().getReadBuffer().readableBytes();
    }

    /** 仅原版 replaceWithPacketData 成功返回后，才把完整区块计入真实接收/落地指标。 */
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void hassium$recordNativeChunkMetric(
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet,
            CallbackInfo ci) {
        if (!this.hassium$recordNativeChunk) {
            return;
        }
        this.hassium$recordNativeChunk = false;
        io.github.limuqy.mc.hassium.network.NativeChunkMetrics.recordAppliedFullChunk(
                io.github.limuqy.mc.hassium.network.NativeChunkMetrics.currentDimension(),
                this.hassium$nativeChunkX, this.hassium$nativeChunkZ, this.hassium$nativeChunkPayloadBytes);
    }


    /**
     * 服务端 chunk cache 半径捕获：影子虚拟玩家 tracking 的选柱半径来源
     * （ShadowTrackingSession 据此设置影子 ChunkMap 视距，并与服务端
     * {@code ShadowPullRequestValidator} 的 maxDistance = 真实视距+1 对齐）。
     */
    @Inject(method = "handleSetChunkCacheRadius", at = @At("TAIL"))
    private void hassium$onServerViewDistance(
            net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket packet,
            CallbackInfo ci) {
        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession
                .setServerViewDistance(packet.getRadius());
    }

    /**
     * 玩家登录时初始化缓存系统
     */
    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void hassium$onLogin(net.minecraft.network.protocol.game.ClientboundLoginPacket packet, CallbackInfo ci) {
        ClientLifecycleHelper.onLogin();
        // 服务端 chunk cache 半径捕获：1.20.1 与 1.21.1+ 的 placeNewPlayer/placeNewPlayer
        // 等价路径都只把 viewDistance 放在登录包 chunkRadius 里（join 时不发
        // SetChunkCacheRadius）。须在 onLogin() 之后赋值——onLogin 的会话 reset 会清半径。
        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession
                .setServerViewDistance(packet.chunkRadius());
    }


#if MC_VER < MC_1_21_1
    /**
     * 断开连接时清理（仅 1.20.1：onDisconnect 仍在 ClientPacketListener）
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hassium$onDisconnect(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }
#endif


    /**
     * 真服 Forget 保留：OVD 窗内取消 drop（玩家走出权威圈时柱尚在 client 窗）。
     * 1.20.1 与 1.21.1+ 方法名一致。
     */
    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$retainOvdOnForget(
            net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket packet,
            CallbackInfo ci) {
        try {
            net.minecraft.world.level.ChunkPos pos =
                    io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat.forgetChunkPos(packet);
            if (io.github.limuqy.mc.hassium.client.OvdClientLifecycle.shouldRetainOnForget(pos)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // OVD 保留失败则走原版 drop
        }
    }


    // ===== 方块更新转发（T2）：HEAD 注入 3 类方块包 handler，不 cancel、不解析、纯转发 =====
    // handler 方法名/参数 mojmap 全段一致（1.20.1 / 1.21.11 已双版本验证），无需 #if 分界。

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void hassium$onBlockUpdate(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        ClientMetadataHandler.forwardBlockUpdate(packet);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void hassium$onChunkBlocksUpdate(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        ClientMetadataHandler.forwardBlockUpdate(packet);
    }

    @Inject(method = "handleBlockEntityData", at = @At("HEAD"))
    private void hassium$onBlockEntityData(net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        ClientMetadataHandler.forwardBlockUpdate(packet);
    }

}
