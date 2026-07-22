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

        // 检查黑名单：已在别处压缩的包不聚合，避免双重压缩
        String packetTypeId = packetType.toString();
        if (!PacketCompressionBlacklist.shouldCompress(packetTypeId)) {
            Constants.LOG.debug("Packet {} is in compression blacklist, skipping aggregation", packetTypeId);
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
}
