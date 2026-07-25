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
    /** 1-based 端点序号（Data Plane Server 接受该连接的端口序号），供诊断日志识别是哪条副连接。0 表示未指定。 */
    public final int portIdx;
    private boolean active = true;

    public PlayerChannel(Channel channel, int weight) {
        this(channel, weight, null, 0);
    }

    public PlayerChannel(Channel channel, int weight, byte[] aesKey) {
        this(channel, weight, aesKey, 0);
    }

    public PlayerChannel(Channel channel, int weight, byte[] aesKey, int portIdx) {
        this.channel = channel;
        this.weight = weight;
        this.aesKey = aesKey;
        this.portIdx = portIdx;
    }

    public boolean isActive() { return active && channel != null && channel.isActive(); }
    public boolean isWritable() { return channel != null && channel.isWritable(); }
    public void setActive(boolean active) { this.active = active; }

    public void close() { if (channel != null && channel.isOpen()) channel.close(); }
}
