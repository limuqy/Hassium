package io.github.limuqy.mc.hassium.network;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ZSTD 协商状态追踪器
 * <p>
 * 追踪每个连接是否已完成 ZSTD 压缩协商。
 */
public class ZstdNegotiationTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ZstdNegotiation");
    private static final ConcurrentMap<Channel, Boolean> negotiatedChannels = new ConcurrentHashMap<>();

    /**
     * 标记通道已协商支持 ZSTD
     */
    public static void markNegotiated(Channel channel) {
        negotiatedChannels.put(channel, true);
        LOGGER.debug("Channel {} marked as ZSTD negotiated", channel.remoteAddress());
    }

    /**
     * 检查通道是否已协商支持 ZSTD
     */
    public static boolean isZstdNegotiated(Channel channel) {
        return negotiatedChannels.getOrDefault(channel, false);
    }

    /**
     * 移除通道（断开连接时调用）
     */
    public static void removeChannel(Channel channel) {
        negotiatedChannels.remove(channel);
        LOGGER.debug("Channel {} removed from ZSTD negotiation tracker", channel.remoteAddress());
    }

    /**
     * 清理所有已断开的通道
     */
    public static void cleanupDisconnectedChannels() {
        negotiatedChannels.entrySet().removeIf(entry -> !entry.getKey().isActive());
    }
}
