package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端数据包监听器
 * <p>
 * 负责初始化缓存系统和断开连接时清理。
 * 元数据处理逻辑在 {@link io.github.limuqy.mc.hassium.network.ClientMetadataHandler} 中。
 * <p>
 * M2: 缓存存储初始化异步化 —— handleLogin 时在后台线程完成 ClientHassiumStorage 创建。
 * <p>
 * 1.20.2+：{@code onDisconnect} 已上移到 {@code ClientCommonPacketListenerImpl}，
 * 由 {@link MixinClientCommonPacketListenerImpl} 注入。
 * <p>
 * 超视渲染：
 * <ul>
 *   <li>拦截 {@code handleForgetLevelChunk}，环带内取消 drop，原地标 renderOnly</li>
 *   <li>改写 {@code handleSetChunkCacheRadius} 的半径参数，避免 Storage 被缩回 serverVD</li>
 * </ul>
 * <p>
 * 共享的清理 / 初始化逻辑已移至 {@link ClientLifecycleHelper}（非 Mixin 类），
 * 因 Mixin 0.8.7 不允许 Mixin 类中存在非 private 的静态方法。
 */
@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public class MixinClientPacketListener {

    /**
     * 玩家登录时初始化缓存系统
     */
    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void hassium$onLogin(net.minecraft.network.protocol.game.ClientboundLoginPacket packet, CallbackInfo ci) {
        ClientLifecycleHelper.onLogin();
    }

#if MC_VER < MC_1_20_2
    /**
     * 断开连接时清理（仅 1.20.1：onDisconnect 仍在 ClientPacketListener）
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hassium$onDisconnect(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }
#endif

    /**
     * 服务端 Forget：若区块仍在超视渲染环带，取消 drop，原地保留为 renderOnly。
     * <p>
     * 避免「卸载 → 再读缓存」多余往返；cancel 后也不跑 light removal（块仍在渲染）。
     */
    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$onForgetLevelChunk(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
#if MC_VER < MC_1_20_2
        ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
#else
        ChunkPos pos = packet.pos();
#endif
        if (ViewDistanceExtensionService.getInstance().tryRetainOnServerForget(pos)) {
            ci.cancel();
        }
    }

    /**
     * 服务端 {@code SetChunkCacheRadius} 仍会写入 options.serverRenderDistance（供超视渲染环带用），
     * 但 {@code ClientChunkCache.updateViewRadius} 必须用 clientVD，否则 Storage 缩回 server 半径：
     * 环带块 inRange=false → apply 被静默丢弃 → ERROR 风暴 + miss 重试风暴。
     */
    @ModifyArg(
            method = "handleSetChunkCacheRadius",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;updateViewRadius(I)V"
            ),
            index = 0
    )
    private int hassium$keepClientChunkCacheRadius(int serverRadius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSingleplayerServer() != null) {
            return serverRadius;
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientCacheEnabled() || !cfg.isViewDistanceExtensionEnabled()) {
            return serverRadius;
        }
        int clientVD = ViewDistanceExtensionService.resolveEffectiveClientVD(mc);
        // 永不小于 server 半径；超视渲染开启时抬到 clientVD
        return Math.max(serverRadius, clientVD);
    }
}
