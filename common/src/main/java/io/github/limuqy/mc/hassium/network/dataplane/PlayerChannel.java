package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.channel.Channel;

/**
 * 单条 Data 通道封装。
 * 在单元测试中可用匿名子类 mock isActive/isWritable。
 */
public class PlayerChannel {
    public final Channel channel;
    public final int weight;
    /** 该连接的派生写密钥（AES-128 key, 来自 HKDF）。服务端写、客户端读需一致。 */
    public final byte[] aesKey;
    private boolean active = true;

    public PlayerChannel(Channel channel, int weight) {
        this(channel, weight, null);
    }

    public PlayerChannel(Channel channel, int weight, byte[] aesKey) {
        this.channel = channel;
        this.weight = weight;
        this.aesKey = aesKey;
    }

    public boolean isActive() { return active && channel != null && channel.isActive(); }
    public boolean isWritable() { return channel != null && channel.isWritable(); }
    public void setActive(boolean active) { this.active = active; }

    public void close() { if (channel != null && channel.isOpen()) channel.close(); }
}
