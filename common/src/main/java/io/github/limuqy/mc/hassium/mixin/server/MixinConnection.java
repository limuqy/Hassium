package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshakeManager;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.protocol.PullResponseDecodeQueue;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Connection lifecycle, send callback metadata and strict v3 native decode failures.
 * Complete packet bytes are intercepted by the PacketEncoder-side pipeline.
 */
@Mixin(value = Connection.class, priority = 1)
public class MixinConnection {

    @Shadow
    private PacketListener packetListener;

    @Shadow
    private PacketFlow receiving;

    @Shadow private io.netty.channel.Channel channel;

#if MC_VER >= MC_1_21_1
    // Only the reconfigurable ProtocolInfo pipeline can pause an already active v3 FIFO.
    // Legacy setProtocol runs during initial login, before v3 is installed; leave it untouched.
    @Inject(method = "setupInboundProtocol", at = @At("TAIL"))
    private void hassium$resumeByteFifo(net.minecraft.network.ProtocolInfo<?> protocol, PacketListener listener, CallbackInfo ci) {
        if (receiving == PacketFlow.CLIENTBOUND) io.github.limuqy.mc.hassium.protocol.egress.EgressPipeline.inboundReady(channel);
    }
#endif
#if MC_VER < MC_1_21_1
    @Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
    private void hassium$consumeLoginQueryAnswer(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (receiving != PacketFlow.SERVERBOUND || !(packet instanceof net.minecraft.network.protocol.login.ServerboundCustomQueryPacket query)) return;
        if (query.getTransactionId() != io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake.TRANSACTION_ID) return;
        if (LoginHandshakeManager.consumeQueryAnswerAtChannel((Connection)(Object)this, query.getData())) ci.cancel();
    }
#endif
    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void hassium$strictDecode(ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
        if (receiving == PacketFlow.CLIENTBOUND && ctx.pipeline().get("hassium_ingress_v3") != null) {
            io.github.limuqy.mc.hassium.protocol.egress.EgressPipeline.disconnectInvalid((Connection)(Object)this, ctx, cause);
            ci.cancel();
        }
    }

    @Inject(method = "doSendPacket", at = @At("HEAD"))
#if MC_VER < MC_1_21_1
    private void hassium$beginWrite(Packet<?> packet, net.minecraft.network.PacketSendListener listener, net.minecraft.network.ConnectionProtocol target, net.minecraft.network.ConnectionProtocol previous, CallbackInfo ci) {
#elif MC_VER < MC_1_21_6
    private void hassium$beginWrite(Packet<?> packet, net.minecraft.network.PacketSendListener listener, boolean flush, CallbackInfo ci) {
#else
    private void hassium$beginWrite(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
#endif
        if (receiving == PacketFlow.SERVERBOUND) {
            io.github.limuqy.mc.hassium.protocol.egress.EgressPipeline.callback(channel, listener != null);
        }
    }

    @Inject(method = "doSendPacket", at = @At("RETURN"))
#if MC_VER < MC_1_21_1
    private void hassium$endWrite(Packet<?> packet, net.minecraft.network.PacketSendListener listener, net.minecraft.network.ConnectionProtocol target, net.minecraft.network.ConnectionProtocol previous, CallbackInfo ci) {
#elif MC_VER < MC_1_21_6
    private void hassium$endWrite(Packet<?> packet, net.minecraft.network.PacketSendListener listener, boolean flush, CallbackInfo ci) {
#else
    private void hassium$endWrite(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
#endif
        if (receiving == PacketFlow.SERVERBOUND) {
            io.github.limuqy.mc.hassium.protocol.egress.EgressPipeline.callback(channel, false);
        }
    }

    // review-fix: T7-59: handler 统一加 hassium$ 前缀（Mixin 惯例，避免与目标类未来同名成员 merge 冲突）
    // 拦截点分版本（真相源 = vanilla Connection.send 重载集 + ServerCommonPacketListenerImpl.send 的委托目标）：
    // - 1.20.1：无 ServerCommonPacketListenerImpl 中间层，ServerGamePacketListenerImpl 直调双参
    //   send(Packet, PacketSendListener)——拦双参即全部发送路径。
    // - 1.20.5+（1.21.1-1.21.5）：发包统一经 ServerCommonPacketListenerImpl.send 直调三参
    //   send(Packet, PacketSendListener, boolean)，双参重载只剩 Connection.send(Packet) 单参入口在用——
    //   拦双参会整段漏包（1.21.1 实证：debug.log 0 条拦截日志，聚合静默失效）。只拦三参：
    //   单参→双参→三参全部汇聚，天然无重复拦截。
    // - 1.21.6+：三参第二参类型改为 ChannelFutureListener，结构同上。
#if MC_VER < MC_1_21_1
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void hassium$onSendPacket(Packet<?> packet, net.minecraft.network.PacketSendListener listener, CallbackInfo ci) {
#elif MC_VER < MC_1_21_6
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"))
    private void hassium$onSendPacket(Packet<?> packet, net.minecraft.network.PacketSendListener listener, boolean flush, CallbackInfo ci) {
#else
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void hassium$onSendPacket(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
#endif
        hassium$tryAggregate(packet, listener != null, ci);
    }
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

        // 实体更新包实测计数（帧率压力的输入；只计数，不改变投递语义）
        if (io.github.limuqy.mc.hassium.server.entity.EntityPacketCounters.isEntityUpdatePacket(packet)) {
            io.github.limuqy.mc.hassium.server.entity.EntityPacketCounters.observe(self);
        }

        // Native PacketEncoder captures the bytes after exactly one invocation of the original codec.
    }

    // review-fix: T7-60: 带描述符注入 disconnect——1.21.1+ 有 Component/DisconnectionDetails 双重载
    // （Component 委托 Details），无描述符会命中两个重载致清理逻辑重复执行
#if MC_VER < MC_1_21_1
    @Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
#else
    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
#endif
    private void hassium$onDisconnect(CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        HassiumConnectionRegistry.markDisabled(self);
        io.github.limuqy.mc.hassium.server.entity.EntityPacketCounters.forget(self);
        // 字典 rollout：断连解除其 offer ACK 门控（或触发阻断者离开后的重试）
        io.github.limuqy.mc.hassium.protocol.DictionaryManager.onConnectionGone(self);
        // review-fix: 仅客户端连接断连才清空 pull 解码队列——队列是进程级单例，
        // LAN/集成服上远程玩家断连不能清掉主机客户端的在途 pull 响应（review §1.3）
        if (receiving == PacketFlow.CLIENTBOUND) {
            PullResponseDecodeQueue.discard();
        }
        LoginHandshakeManager.onDisconnect(self);
    }
}
