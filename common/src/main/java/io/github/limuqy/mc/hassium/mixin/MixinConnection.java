package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.network.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.netty.channel.Channel;
import io.github.limuqy.mc.hassium.network.PacketCompressionBlacklist;
import io.github.limuqy.mc.hassium.network.PacketTypeHelper;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.Connection;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import net.minecraft.network.PacketListener;
#if MC_VER < MC_1_21_6
import net.minecraft.network.PacketSendListener;
#else
import io.netty.channel.ChannelFutureListener;
#endif
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截网络连接发送数据包，实现 Hassium 聚合压缩
 * <p>
 * 注意：区块数据包不再经过此拦截，由 ServerChunkPushManager 直接发送。
 * <p>
 * 1.21.6+：{@code Connection.send} 第二参数由 {@code PacketSendListener} 改为 {@code ChannelFutureListener}。
 */
@Mixin(value = Connection.class, priority = 1)
public class MixinConnection {

    @Shadow
    private PacketListener packetListener;

    @Shadow
    private Channel channel;

#if MC_VER < MC_1_21_6
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, PacketSendListener sendListener, CallbackInfo ci) {
        hassium$tryAggregate(packet, sendListener != null, ci);
    }
#else
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, ChannelFutureListener sendListener, CallbackInfo ci) {
        hassium$tryAggregate(packet, sendListener != null, ci);
    }
#endif

    /**
     * 聚合拦截公共逻辑（需回调的包不聚合）。
     */
    @Unique
    private void hassium$tryAggregate(Packet<?> packet, boolean hasSendListener, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;

        // 聚合只在服务端进行，客户端不聚合
        if (!(packetListener instanceof ServerGamePacketListenerImpl)) {
            return;
        }

        // 聚合包自身不拦截
        if (PacketTypeHelper.isAggregationPacket(packet)) {
            return;
        }

        // 获取包类型
#if MC_VER < MC_1_21_11
        ResourceLocation
#else
        Identifier
#endif
        packetType = PacketTypeHelper.getPacketType(packet);
        if (packetType == null) {
            // 无法识别的包不聚合，直接发送
            return;
        }

        // 检查黑名单 / 高频排除：控制面、独立压缩通道、实体高频包不聚合
        String packetTypeId = packetType.toString();
        if (!PacketCompressionBlacklist.shouldAggregate(packetTypeId)) {
            Constants.LOG.debug("Packet {} skipped aggregation (blacklist or high-freq)", packetTypeId);
            return;
        }

        // 检查连接是否启用聚合
        boolean isActive = HassiumConnectionRegistry.isActive(self);
        if (!isActive) {
            return;
        }

        // 检查聚合配置开关
        if (!HassiumConfigService.getInstance().isPacketAggregationEnabled()) {
            return;
        }

        // 需要回调的包不聚合，直接发送
        if (hasSendListener) {
            HassiumAggregationManager.flushConnectionSync(self);
            return;
        }

        // 将包交给聚合管理器（原版包和自定义包都聚合）
        Constants.LOG.debug("Aggregating packet: {}", packetType);
        HassiumAggregationManager.takeOver(packet, self);
        ci.cancel();
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void hassium$onDisconnect(CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        HassiumConnectionRegistry.markDisabled(self);
        HassiumAggregationManager.discardConnection(self);
        if (this.channel != null) {
            ZstdNegotiationTracker.removeChannel(this.channel);
        }
    }

    /**
     * 控制面断连统一入口（L2 恢复启动点迁移）：
     * <ul>
     *   <li>主线程 + channel 仍 open → 用户主动退出（PauseScreen 断开），标记后 failover gate 拦截。</li>
     *   <li>其余（netty 线程 / 通道已关闭）→ 被动断连：驱动控制面恢复。主控断开与候选失败走同一入口，
     *       orchestrator 剔除 active + 取下一候选，天然形成轮转闭环；服务端侧 instance==null no-op。</li>
     * </ul>
     *
     * <p>带签名注入：1.21.5+ 的 {@code disconnect(DisconnectionDetails)} 重载不标记
     * （exceptionCaught 非 timeout 走它）。
     */
    @Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void hassium$onControlDisconnect(CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()) {
            io.github.limuqy.mc.hassium.Constants.LOG.info(
                    "[diag] Connection.disconnect(Component) thread={} connected={} channelNull={} inEventLoop={}",
                    Thread.currentThread().getName(), self.isConnected(), this.channel == null,
                    this.channel != null && this.channel.eventLoop().inEventLoop());
        }
        if (self.isConnected() && this.channel != null && !this.channel.eventLoop().inEventLoop()) {
            io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.markUserInitiatedDisconnect();
            return;
        }
        // 被动断连（netty 线程 / 通道已关闭）：驱动控制面恢复
        io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint active =
                io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity
                        .activeEndpointFromChannel(this.channel);
        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity
                .onPrimaryDisconnected(active, "channel_inactive");
    }

    /**
     * L2 无感切换（{@code network.dataPlane.recoveryFreeze=false}）：恢复窗口内吞掉旧连接的全部 C2S 包。
     * <p>
     * 无感模式世界不冻结、照常 tick，玩家输入/区块请求会经已断开的旧 connection 发送：
     * 每次 writeAndFlush 都立即失败（failed future + disconnect 噪音）；更重要的是这些包
     * 到不了已登出的服务器玩家——直接拦截，让本地预测照常（移动/挖掘客户端侧即时生效），
     * 服务器无感知，恢复成功后 setLevel 以服务器状态重置：位置回退到断线点、刚挖的方块还原，
     * 体感如同突然延迟变高卡了一下。
     * <p>
     * 只拦 {@code mc.getConnection()}（=旧 player.connection）：候选连接（ConnectScreen 持有）
     * 不受影响；handleLogin 拆 player 后 getConnection() 先变 null、再指向新连接，均放行。
     * 全版本生效（≥1.21.6 的 send 第二参数为 {@code ChannelFutureListener}，签名分流）。
     */
#if MC_VER < MC_1_21_6
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$suppressC2SWhileSeamlessRecovery(
            Packet<?> packet, net.minecraft.network.PacketSendListener listener,
            CallbackInfo ci) {
#else
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$suppressC2SWhileSeamlessRecovery(
            Packet<?> packet, io.netty.channel.ChannelFutureListener listener,
            CallbackInfo ci) {
#endif
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isRecoveryFreeze()) {
            return;
        }
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            return;
        }
        // 判断逻辑抽到普通类（ClientFailoverIdentity）：mixin handler 方法体直接引用
        // 客户端类会在服务端变换时崩溃（ClassMetadataNotFoundException: net.minecraft.client.Minecraft）
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity
                .isCurrentClientConnection((Object) this)) {
            return;
        }
        ci.cancel();
    }
}
