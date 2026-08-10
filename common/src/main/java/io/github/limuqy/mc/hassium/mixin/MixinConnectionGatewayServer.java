package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.server.GatewayConnectionAccessor;
import io.github.limuqy.mc.hassium.server.GatewayPlayerBridge;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
#if MC_VER < MC_1_21_6
import net.minecraft.network.PacketSendListener;
#else
import io.netty.channel.ChannelFutureListener;
#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.SocketAddress;

/**
 * 网关 Connection 桥注入（T12 主控侧）：{@code sendPacket} HEAD 拦截 → 网关帧
 * 路由；channel/address 私有字段 accessor（GatewayPlayerBridge 创建假 Connection
 * 时注入 EmbeddedChannel——{@code Connection.hasDisconnected()} 依赖非 null 且
 * open 的 channel，推送链的 {@code player.hasDisconnected()} gate 才不误杀）。
 *
 * <p>客户端侧零影响：{@link GatewayPlayerBridge#routeS2C} 查桥状态表，非网关
 * 连接原样放行（拦截点即 return false，vanilla 发送不受扰）。
 *
 * <p>实现 {@link GatewayConnectionAccessor}：mixin 把接口挂到目标类 Connection，
 * 业务代码（GatewayPlayerBridge）cast 接口而非本 mixin 类——fabric Knot 运行时
 * 禁止直接引用 mixin 类（T10 真实运行修复，见接口 javadoc）。
 *
 * <p>sendPacket 描述符按锚点分段：
 * <ul>
 *   <li>1.20.1：{@code sendPacket(Packet, PacketSendListener)}（2 参）</li>
 *   <li>1.20.2–1.21.5：{@code sendPacket(Packet, PacketSendListener, boolean)}（3 参）</li>
 *   <li>1.21.6+：{@code sendPacket(Packet, ChannelFutureListener, boolean)}</li>
 * </ul>
 */
@Mixin(value = Connection.class, priority = 5)
public abstract class MixinConnectionGatewayServer implements GatewayConnectionAccessor {

    @Accessor("channel")
    public abstract void hassium$setGatewayChannel(Channel channel);

    @Accessor("address")
    public abstract void hassium$setGatewayAddress(SocketAddress address);

    /** 1.21.11 setListener 非 public：统一经字段 accessor 设置监听器。 */
    @Accessor("packetListener")
    public abstract void hassium$setGatewayPacketListener(PacketListener listener);

#if MC_VER < MC_1_20_2
    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$routeS2CToGateway(Packet<?> packet, PacketSendListener sendListener, CallbackInfo ci) {
        if (GatewayPlayerBridge.routeS2C((Connection) (Object) this, packet)) {
            ci.cancel();
        }
    }
#elif MC_VER < MC_1_21_6
    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$routeS2CToGateway(Packet<?> packet, PacketSendListener sendListener, boolean flush, CallbackInfo ci) {
        if (GatewayPlayerBridge.routeS2C((Connection) (Object) this, packet)) {
            ci.cancel();
        }
    }
#else
    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$routeS2CToGateway(Packet<?> packet, ChannelFutureListener sendListener, boolean flush, CallbackInfo ci) {
        if (GatewayPlayerBridge.routeS2C((Connection) (Object) this, packet)) {
            ci.cancel();
        }
    }
#endif
}
