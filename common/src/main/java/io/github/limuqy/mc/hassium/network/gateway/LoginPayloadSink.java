package io.github.limuqy.mc.hassium.network.gateway;

import io.netty.buffer.ByteBuf;

/**
 * LOGIN_C2S 帧注入缝（T5 帧类型 LOGIN_C2S(9)/LOGIN_S2C(10) 的 master 侧消费端）。
 *
 * <p>登录阶段（玩家会话尚未建立，无 UUID）的 C2S 帧走本缝，与
 * {@link C2SPayloadSink}（PLAY 阶段、会话已建立）分开。平台侧登录桥
 * （T5 登录中继配对）实现：解码 LOGIN SERVERBOUND 包 → 驱动原版
 * ServerLoginPacketListener 登录链 → 登录完成后 {@link GatewayChannel#attachPlayer}
 * 附着玩家会话并回 LOGIN_S2C。
 *
 * <p>线程：Netty event loop 线程调用；payload 为 retained slice，实现体负责释放。
 */
@FunctionalInterface
public interface LoginPayloadSink {

    /**
     * 注入一条 LOGIN_C2S 帧 payload 到登录桥。
     *
     * @param channel 来源帧连接（登录桥经其回 LOGIN_S2C / 附着会话）
     * @param payload retained slice；实现体负责释放
     */
    void accept(GatewayChannel channel, ByteBuf payload);

    /**
     * 注入一条 CONFIG_C2S 帧 payload（T10 帧类型 11）到登录桥。默认实现退化为
     * {@link #accept}——网关桥实现按当前监听器阶段感知解码（登录监听器期 → 登录协议，
     * 配置监听器期 → configuration 协议），帧类型不决定解码协议。
     *
     * @param channel 来源帧连接
     * @param payload retained slice；实现体负责释放
     */
    default void acceptConfig(GatewayChannel channel, ByteBuf payload) {
        accept(channel, payload);
    }
}
