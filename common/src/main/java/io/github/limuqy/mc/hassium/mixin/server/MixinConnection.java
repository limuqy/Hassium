package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshakeManager;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.protocol.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.protocol.AggregationDecodeQueue;
import io.github.limuqy.mc.hassium.protocol.AggregatedPacketExporter;
import io.github.limuqy.mc.hassium.protocol.PullResponseDecodeQueue;
import io.github.limuqy.mc.hassium.protocol.PacketCompressionBlacklist;
import io.github.limuqy.mc.hassium.protocol.PacketTypeHelper;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
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
 * 拦截网络连接发送数据包，实现 Hassium 聚合压缩（服务端 → vanilla 直连）。
 * <p>
 * 注意：区块数据包不再经过此拦截，由 ServerChunkPushManager 直接发送。
 * <p>
 * 1.21.6+：{@code Connection.send} 第二参数由 {@code PacketSendListener} 改为
 * {@code ChannelFutureListener}。
 */
@Mixin(value = Connection.class, priority = 1)
public class MixinConnection {

    @Shadow
    private PacketListener packetListener;

    @Shadow
    private PacketFlow receiving;

    /**
     * 客户端入站：在 {@code PacketUtils} 跳主线程之前拦聚合帧，只拷贝 body 入 FIFO 工人。
     */
    @Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
    private void hassium$interceptClientAggregation(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (receiving != PacketFlow.CLIENTBOUND) {
            return;
        }
        if (!PacketTypeHelper.isAggregationPacket(packet)) {
            return;
        }
        byte[] data = PacketPayloadCompat.extractPayloadData(packet);
        if (data != null && data.length > 0) {
            AggregationDecodeQueue.enqueue((Connection) (Object) this, data);
        }
        // 1.20.1 getData() 会消费 buffer：识别为聚合帧后必须 cancel，不能再交给原版。
        ci.cancel();
    }

    /**
     * 服务端入站：提前消费 1.20.1 Hassium login query 应答。
     * 应答可能晚于 listener 切到 Game —— 原版 handleCustomQueryPacket 会
     * ClassCastException（首连偶发「此服务器发送了一个无效的数据包」）。
     */
    @Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
    private void hassium$consumeLoginQueryAnswer(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (receiving != PacketFlow.SERVERBOUND) {
            return;
        }
#if MC_VER < MC_1_21_1
        if (!(packet instanceof net.minecraft.network.protocol.login.ServerboundCustomQueryPacket query)) {
            return;
        }
        if (query.getTransactionId() != io.github.limuqy.mc.hassium.protocol.handshake.LoginHandshake.TRANSACTION_ID) {
            return;
        }
        net.minecraft.network.FriendlyByteBuf data = query.getData();
        Connection self = (Connection) (Object) this;
        if (LoginHandshakeManager.consumeQueryAnswerAtChannel(self, data)) {
            ci.cancel();
        }
#endif
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
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void hassium$onSendPacket(Packet<?> packet, net.minecraft.network.PacketSendListener sendListener, CallbackInfo ci) {
        hassium$tryAggregate(packet, sendListener != null, ci);
    }
#elif MC_VER < MC_1_21_6
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void hassium$onSendPacket(Packet<?> packet, net.minecraft.network.PacketSendListener sendListener, boolean flushNow, CallbackInfo ci) {
        hassium$tryAggregate(packet, sendListener != null, ci);
    }
#else
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void hassium$onSendPacket(Packet<?> packet, io.netty.channel.ChannelFutureListener sendListener, boolean flushNow, CallbackInfo ci) {
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

        // debug.exportAggregatedPackets：导出点在握手/聚合/黑名单 gating 之前，
        // 单人/LAN 集成服的本机 memory 连接（不握手、不聚合）同样导出
        AggregatedPacketExporter.exportSendPacket(packet, self);

        // 实体更新包实测计数（帧率压力的输入；只计数，不改变投递语义）
        if (io.github.limuqy.mc.hassium.server.entity.EntityPacketCounters.isEntityUpdatePacket(packet)) {
            io.github.limuqy.mc.hassium.server.entity.EntityPacketCounters.observe(self);
        }

        // 聚合包自身不拦截
        if (PacketTypeHelper.isAggregationPacket(packet)) {
            return;
        }
        // Bundle 包直发：1.21.1+ play codec 表只注册 bundle_delimiter，bundle 本身
        // 无法经 IdDispatchCodec 序列化（EncoderException "Sending unknown packet"）；
        // vanilla 靠管线层 PacketBundlePacker 拆包，而本 mixin 在 Connection.send 层
        // 拦截，bundle 到不了 packer。若不排除，takeOver 序列化失败后 ci.cancel()
        // 已执行 → 包被静默丢弃（实体出生包组 ServerEntity.addPairing 走此路径，
        // 症状 = 聚合激活后实体不可见）。1.20.1 侧 getVanillaIdentifier 对 bundle
        // 返回 null 本就直发，此处排除使两段行为一致。
        if (packet instanceof net.minecraft.network.protocol.BundlePacket) {
            return;
        }

        // 获取包类型
        var packetType = PacketTypeHelper.getPacketType(packet);
        if (packetType == null) {
            // 无法识别的包不聚合，直接发送
            return;
        }

#if MC_VER >= MC_1_21_1
        // mod / 加载器注册的 S2C payload：客户端 handler 按具体类型对象分发，
        // 聚合重放的 RawCustomPayload 会 ClassCastException（neoforge:custom_time_packet 实证）→ 直发
        if (PacketPayloadCompat.isModdedS2CCustomPayload(packet)) {
            return;
        }
#endif

        // 检查黑名单 / 高频排除：控制面、独立压缩通道、区块图控制包不聚合
        String packetTypeId = packetType.toString();
        if (!PacketCompressionBlacklist.shouldAggregate(packetTypeId)) {
            Constants.LOG.debug("Packet {} skipped aggregation (blacklist or high-freq)", packetTypeId);
            return;
        }

        // 会话级序列化失败短路：曾无法聚合的类型（如 client 类引用的第三方 payload）直接放行直发
        if (HassiumAggregationManager.isAggregationIncompatible(packetTypeId)) {
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
        // 仅入缓冲成功才 cancel；失败（序列化 null/异常、缓冲溢出降级）必须让原版 send 继续，否则丢包
        if (HassiumAggregationManager.takeOver(packet, self)) {
            ci.cancel();
        }
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
        HassiumAggregationManager.discardConnection(self);
        AggregationDecodeQueue.discard(self);
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
