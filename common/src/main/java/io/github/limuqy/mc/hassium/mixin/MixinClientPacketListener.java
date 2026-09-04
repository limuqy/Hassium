package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端数据包监听器
 * <p>
 * 负责初始化缓存系统和断开连接时清理。
 * 元数据处理逻辑在 {@link io.github.limuqy.mc.hassium.network.ClientMetadataHandler} 中。
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
        if (io.github.limuqy.mc.hassium.network.ShadowPullClient.handleNativeChunk(packet)) {
            this.hassium$recordNativeChunk = false;
            ci.cancel();
            return;
        }
        if (!io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance().isApplyInProgress()
                && io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.isEnabled()) {
            io.github.limuqy.mc.hassium.network.seedgen.ShadowVanillaLightPipeline.submitVisible(
                    io.github.limuqy.mc.hassium.network.seedgen.ShadowVanillaLightPipeline.currentDimension(),
                    new net.minecraft.world.level.ChunkPos(packet.getX(), packet.getZ()), packet,
                    io.github.limuqy.mc.hassium.network.ClientChunkHandler.TraceOrigin.SERVER_PUSH);
            this.hassium$recordNativeChunk = false;
            ci.cancel();
            return;
        }
        this.hassium$recordNativeChunk = !io.github.limuqy.mc.hassium.network.ClientChunkPipeline
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
     * 玩家登录时初始化缓存系统
     */
    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void hassium$onLogin(net.minecraft.network.protocol.game.ClientboundLoginPacket packet, CallbackInfo ci) {
        ClientLifecycleHelper.onLogin();
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
