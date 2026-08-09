package io.github.limuqy.mc.hassium.network.gateway;

import io.netty.buffer.ByteBuf;

/**
 * C2S 帧 payload 注入缝（T11 骨架，平台侧实现体）。
 *
 * <p>主控侧平台把帧侧收上来的原版 C2S 包字节解码为原版 {@code Packet} 后，
 * 注入对应玩家会话的既有处理链（ServerChunkPushManager.handleXxx /
 * vanilla ServerGamePacketListener 处理），并在注册会话时
 * {@link GatewayPlayerSession#setC2SSink} 挂载。
 *
 * <p>线程：Netty event loop 线程调用；实现体必须自行切换线程（如
 * {@code server.execute}）或保证处理函数线程安全。
 *
 * <p>payload 所有权：为 retained slice（{@code retainedDuplicate()}），
 * 实现体负责消费/释放；不再需要时必须 {@code release()}。
 */
@FunctionalInterface
public interface C2SPayloadSink {

    /**
     * 注入一条 C2S 包 payload 到玩家处理链。
     *
     * @param playerId 会话玩家 UUID（与握手票据/登录桥一致）
     * @param payload  retained slice；实现体负责释放
     */
    void accept(java.util.UUID playerId, ByteBuf payload);
}
