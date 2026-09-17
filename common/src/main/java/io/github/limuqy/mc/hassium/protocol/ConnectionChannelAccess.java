package io.github.limuqy.mc.hassium.protocol;

import java.lang.reflect.Field;
import net.minecraft.network.Connection;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code Connection → Netty Channel} 反射访问器（跨版本字段名不可靠，按类型查找并缓存）。
 * <p>
 * 历史注记：本类前身为 {@code ZstdPipelineSwitcher}（管线级 ZSTD 替换 vanilla zlib），
 * 该机制已随直连拓扑简化退役——原版压缩层全程不触碰，通道压缩由聚合包内部字典 ZSTD
 * 与区块推送自有压缩承担（见 {@code docs/architecture.md}）。
 */
public final class ConnectionChannelAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ChannelAccess");

    /**
     * Connection.channel 字段缓存（review-fix: T2-75）：热路径首次反射
     * 查找后缓存复用；字段按类型匹配（Forge SRG / Fabric intermediary 字段名不可靠），
     * 类加载器侧共用，双检锁保证线程安全。
     */
    private static volatile Field connectionChannelField;

    private ConnectionChannelAccess() {
    }

    public static Channel getConnectionChannel(Connection connection) {
        if (connection == null) {
            return null;
        }
        try {
            Field channelField = connectionChannelField;
            if (channelField == null) {
                synchronized (ConnectionChannelAccess.class) {
                    channelField = connectionChannelField;
                    if (channelField == null) {
                        channelField = io.github.limuqy.mc.hassium.compat.ReflectionCompat.findFieldByType(
                                Connection.class, Channel.class, false);
                        channelField.setAccessible(true);
                        connectionChannelField = channelField;
                    }
                }
            }
            return (Channel) channelField.get(connection);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to get channel from connection", e);
            return null;
        }
    }
}
