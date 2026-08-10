package io.github.limuqy.mc.hassium.server;

import io.netty.channel.Channel;
import net.minecraft.network.PacketListener;

import java.net.SocketAddress;

/**
 * 网关 Connection 桥注入访问器（T10 真实运行修复）。
 * <p>
 * 背景：{@link GatewayPlayerBridge#createGatewayConnection()} 需要向 vanilla
 * {@code Connection} 注入 EmbeddedChannel/address/packetListener（私有字段）。
 * 原实现直接 cast {@code MixinConnectionGatewayServer}——fabric Knot 运行时
 * 禁止直接引用 mixin 类（Illegal classload request），续流物化路径（T8 单测
 * 环境无 Knot 未暴露）真实部署必崩。修复：mixin 类实现本接口（mixin 会把接口
 * 挂到目标类 Connection 上），业务代码 cast 接口而非 mixin 类。
 */
public interface GatewayConnectionAccessor {

    void hassium$setGatewayChannel(Channel channel);

    void hassium$setGatewayAddress(SocketAddress address);

    void hassium$setGatewayPacketListener(PacketListener listener);
}
