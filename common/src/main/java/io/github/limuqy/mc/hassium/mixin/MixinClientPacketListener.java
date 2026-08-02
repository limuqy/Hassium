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
        // L2 恢复成功收敛（幂等）：热切握手 accepted 后新 player 在 handleLogin 建立（主线程），
        // 此为主控热切成功的统一收敛点。MixinClientTick 的 pendingUdpStart 分支是死代码
        // （deferUdpStart 无调用点），恢复收敛此前永不执行，导致 orchestrator.recovering 悬挂：
        // 无感模式 C2S 拦截持续吞包、AttemptMarker 不清理、fallback 通知延迟到下次进服。
        // orchestrator.recovering==false（首次进服/普通重连）时 onHandshakeAccepted 返回 false，no-op。
        try {
            boolean recovered = io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity
                    .onHandshakeAccepted();
            if (recovered) {
                io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.consumeSuccessfulFallback()
                        .ifPresent(endpoint -> net.minecraft.client.Minecraft.getInstance().gui.getChat().addMessage(
                                net.minecraft.network.chat.Component.literal("[Hassium] 主地址 "
                                        + io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.primaryAddress()
                                        + " 不可用，已通过备用端点 " + endpoint.host() + ":" + endpoint.port()
                                        + " 连接；服务器列表地址和缓存身份仍为主地址。")));
            }
        } catch (Throwable ignored) {
            // 收敛失败不阻断登录
        }
    }

#if MC_VER < MC_1_20_2
    /**
     * L2 定格恢复：新世界接管前拆除冻结的旧 player。
     * <p>
     * vanilla handleLogin 依赖 {@code minecraft.player == null} 才会创建新 LocalPlayer，
     * 而 {@code Minecraft.getConnection()} 返回的正是 {@code player.connection}。
     * 定格期间旧 player（connection=已断开的 ROUND1 连接）被 F1/F2 保留，若不拆除：
     * <ul>
     *   <li>新 player 永不创建，{@code mc.getConnection()} 永远指向断连的 ROUND1 连接</li>
     *   <li>候选连接在 ConnectScreen 退场（setScreen(ReceivingLevelScreen)）后无人 tick，
     *       后续包（chunks、Hassium 握手 S2C）永不处理，恢复永久卡死</li>
     * </ul>
     * <p>
     * 线程约束（关键）：1.20.1 包在 Netty 线程首次分发，HEAD 注入位于
     * {@code PacketUtils.ensureRunningOnSameThread} 之前；Netty 首分发时绝不能做
     * 渲染/UI 操作（clearLevel→setScreen 会抛 RenderSystem wrong thread，候选连接崩溃）。
     * 非主线程直接 return，交给 ensure 排队到主线程重跑后本注入再执行。
     * <p>
     * 主线程只置 {@code player=null}，不调 clearLevel：旧 level 由 vanilla setLevel 直接替换
     * （数据已在断连 dump 落盘），且 clearLevel 的 dropAllTasks 会清掉排队中的握手确认任务。
     */
    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void hassium$teardownFrozenWorld(net.minecraft.network.protocol.game.ClientboundLoginPacket packet,
                                             CallbackInfo ci) {
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isFreezeActive()
                && !io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (!mc.isSameThread()) {
            return;
        }
        mc.player = null;
    }
#endif

#if MC_VER < MC_1_20_2
    /**
     * 断开连接时清理（仅 1.20.1：onDisconnect 仍在 ClientPacketListener）
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hassium$onDisconnect(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
        ClientLifecycleHelper.cleanupOnDisconnect();
    }

    /**
     * L2 恢复窗口 begin / UDP keepLease（在冻结 cancel 之前声明执行）。
     * <p>
     * fabric 的 ClientPlayConnectionEvents.DISCONNECT 也注入 onDisconnect HEAD（priority 999），
     * 与我们的取消注入同点竞争——无论哪边先跑，恢复态 begin 与 stopUdp(keepLease=true) 都必须
     * 已就位（stopUdp keepLease 幂等，双调安全），否则恢复窗口内 finalize 不被抑制、UDP 束被硬关。
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hassium$beginRecoveryState(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            return;
        }
        io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.getInstance().begin(
                java.lang.System.currentTimeMillis() + 60_000L);
        io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle.getInstance()
                .stopUdp(true);
    }

    /**
     * L2 世界定格：恢复窗口中取消 vanilla onDisconnect 方法体（clearLevel + setScreen 均不执行），
     * 世界画面保持冻结；恢复成功 setLevel 或 terminal 回退后再放行。
     */
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void hassium$freezeOnDisconnect(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markFreezeActive(true);
            ci.cancel();
        }
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
