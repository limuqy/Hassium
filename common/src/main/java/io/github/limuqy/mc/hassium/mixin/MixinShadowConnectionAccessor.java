package io.github.limuqy.mc.hassium.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.net.SocketAddress;

/**
 * 影子虚拟玩家连接桩 accessor：注入 EmbeddedChannel / 地址。
 * <p>
 * {@code Connection.channel/address} 为 private 且 {@code channel} 仅在
 * {@code channelActive}（真网络握手）赋值；虚拟玩家没有真网络，经本 accessor
 * 直接注入常开 EmbeddedChannel，使 {@code isConnected()} / {@code hasDisconnected()}
 * 语义为已连接、S2C 发送进入 dummy 管道丢弃（而非积压进 {@code pendingActions} 队列）。
 * <p>
 * 1.20.1–1.21.11 字段名一致（mojmap {@code channel} / {@code address}），零 #if。
 */
@Mixin(Connection.class)
public interface MixinShadowConnectionAccessor {

    @Mutable
    @Accessor("channel")
    void hassium$setShadowChannel(Channel channel);

    @Mutable
    @Accessor("address")
    void hassium$setShadowAddress(SocketAddress address);
}
