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
     * T5/T10 C2S 通路截获（按 packetListener 阶段分发，send HEAD，可 cancel）：
     * <ul>
     *   <li>服务端方向（send 的是 S2C）：零开销放行——先判定服务端监听器，后续客户端类
     *       instanceof 不执行（dedicated server 客户端类不存在，禁止解析）。</li>
     *   <li>客户端登录阶段（ClientHandshakePacketListenerImpl）：纯旁路中继 LOGIN_C2S
     *       （T5；不 cancel——vanilla 登录照常，主控会话由网关独立复刻）。</li>
     *   <li>客户端配置阶段（ClientConfigurationPacketListenerImpl，1.20.2+）：纯旁路中继
     *       CONFIG_C2S（T10；不 cancel——客户端配置由 vanilla TCP 完成，镜像供主控阶段推进）。</li>
     *   <li>客户端 PLAY 阶段（ClientPacketListener）：routeC2S 编码进 outbound（PACKET_C2S），
     *       返回 true 时 cancel 原版发送——原版连接为壳，C2S 全走网关；keep-alive 响应例外
     *       （壳连接保活镜像，下波任务）。未路由（outbound 未开/编码失败）原版放行降级。</li>
     * </ul>
     * 与 {@link #hassium$tryAggregate}（服务端聚合，cancel 语义独立）互斥：两端监听器类型
     * 不相交，顺序无关。
     */
#if MC_VER < MC_1_21_6
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void hassium$routeC2SToGateway(Packet<?> packet, PacketSendListener sendListener, CallbackInfo ci) {
        hassium$routeC2S(packet, ci);
    }
#else
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void hassium$routeC2SToGateway(Packet<?> packet, ChannelFutureListener sendListener, CallbackInfo ci) {
        hassium$routeC2S(packet, ci);
    }
#endif

    @Unique
    private void hassium$routeC2S(Packet<?> packet, CallbackInfo ci) {
        // 服务端方向零开销：S2C 发送不路由（客户端类引用不得解析——dedicated server 无客户端类）
        if (packetListener instanceof ServerGamePacketListenerImpl
                || packetListener instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl
                || packetListener instanceof net.minecraft.server.network.ServerHandshakePacketListenerImpl
                || packetListener instanceof net.minecraft.server.network.ServerStatusPacketListenerImpl) {
            return;
        }
#if MC_VER >= MC_1_20_2
        if (packetListener instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl) {
            return;
        }
#endif
        io.github.limuqy.mc.hassium.network.core.NetworkCore core =
                io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance();
        // 客户端 vanilla Connection 暂存（CONFIG_S2C 分发回退用；登录期同步登记）
        core.setVanillaConnection((Connection) (Object) this);
        if (packetListener instanceof net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl) {
            core.relayLoginPacket(packet);
            return;
        }
#if MC_VER >= MC_1_20_2
        if (packetListener instanceof net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl) {
            core.relayConfigPacket(packet);
            return;
        }
#endif
        if (packetListener instanceof net.minecraft.client.multiplayer.ClientPacketListener) {
            // 壳保活：keep-alive 响应走 vanilla TCP（网关会话为主，壳连接不被服务端踢）
            if (hassium$isKeepAlive(packet)) {
                return;
            }
            if (core.routeC2S(packet)) {
                ci.cancel();
            }
        }
    }

    /** keep-alive 响应判定（1.20.1 在 game 包；1.20.2+ 在 common 包——按监听器阶段已收敛，包类仅防误伤）。 */
    @Unique
    private static boolean hassium$isKeepAlive(Packet<?> packet) {
#if MC_VER < MC_1_20_2
        return packet instanceof net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
#else
        return packet instanceof net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
#endif
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
        io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().setVanillaConnection(null);
        if (this.channel != null) {
            ZstdNegotiationTracker.removeChannel(this.channel);
        }
    }
}
