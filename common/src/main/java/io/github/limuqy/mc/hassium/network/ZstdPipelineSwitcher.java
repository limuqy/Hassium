package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZSTD Pipeline 切换器
 * <p>
 * 负责在运行时动态替换 Netty Pipeline 中的压缩/解压 Handler。
 * 使用上下文编码器（ZstdCompressCtx/ZstdDecompressCtx），利用历史窗口提升压缩率。
 */
public class ZstdPipelineSwitcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PipelineSwitcher");

    private static final String DECOMPRESS_HANDLER_NAME = "decompress";
    private static final String COMPRESS_HANDLER_NAME = "compress";

    private static final int DEFAULT_INSTALL_RETRIES = 25;
    private static final long INSTALL_RETRY_MS = 200L;

    /**
     * 切换到 ZSTD 压缩（使用 SkipAware 编码器，支持聚合包跳过管线压缩）
     */
    public static void switchToZstd(Channel channel, int threshold, int level) {
        if (channel == null) {
            return;
        }
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> switchToZstd(channel, threshold, level));
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();

        removeHandlerSafely(pipeline, DECOMPRESS_HANDLER_NAME);
        removeHandlerSafely(pipeline, COMPRESS_HANDLER_NAME);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Pipeline after removing old handlers: {}", pipeline.names());
        }

        HassiumConfigService config = HassiumConfigService.getInstance();
        boolean magicless = config.isMagiclessZstd();

        String decoderName = findHandlerNameByType(pipeline, PacketDecoder.class, "decoder");
        String encoderName = findHandlerNameByType(pipeline, PacketEncoder.class, "encoder");
        addHandlerBefore(pipeline, decoderName, DECOMPRESS_HANDLER_NAME,
                new ZstdContextDecoder(threshold, true, magicless));
        addHandlerBefore(pipeline, encoderName, COMPRESS_HANDLER_NAME,
                new SkipAwareZstdEncoder(threshold, level, magicless));

        LOGGER.info("Installed ZSTD pipeline (level={}, threshold={}, magicless={})",
                level, threshold, magicless);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Pipeline after installing ZSTD handlers: {}", pipeline.names());
        }
    }

    /**
     * 管线是否已具备 PacketDecoder + PacketEncoder（可安全 addBefore）。
     * <p>
     * 优先按类型匹配；失败时回退到原版固定名 {@code decoder}/{@code encoder}
     *（部分 Forge 管线包装下类型检查可能错过）。
     */
    public static boolean pipelineHasPacketCodec(Channel channel) {
        if (channel == null) {
            return false;
        }
        ChannelPipeline pipeline = channel.pipeline();
        boolean byType = findHandlerNameByType(pipeline, PacketDecoder.class, null) != null
                && findHandlerNameByType(pipeline, PacketEncoder.class, null) != null;
        if (byType) {
            return true;
        }
        return pipeline.get("decoder") != null && pipeline.get("encoder") != null;
    }

    /**
     * markNegotiated 后：管线就绪则立即安装，否则 EventLoop 短间隔重试。
     * {@code onInstalled} 在成功安装后于 EventLoop 上回调（可为 null）。
     */
    public static void switchToZstdWhenReady(Channel channel, int threshold, int level) {
        switchToZstdWhenReady(channel, threshold, level, DEFAULT_INSTALL_RETRIES, null);
    }

    public static void switchToZstdWhenReady(Channel channel, int threshold, int level, Runnable onInstalled) {
        switchToZstdWhenReady(channel, threshold, level, DEFAULT_INSTALL_RETRIES, onInstalled);
    }

    public static void switchToZstdWhenReady(Channel channel, int threshold, int level, int maxRetries,
                                            Runnable onInstalled) {
        if (channel == null) {
            return;
        }
        Runnable attempt = new Runnable() {
            private int remaining = maxRetries;

            @Override
            public void run() {
                if (!channel.isActive()) {
                    LOGGER.warn("Hassium: Channel inactive, abort ZSTD install");
                    return;
                }
                if (pipelineHasPacketCodec(channel)) {
                    switchToZstd(channel, threshold, level);
                    if (onInstalled != null) {
                        onInstalled.run();
                    }
                    return;
                }
                if (remaining <= 0) {
                    LOGGER.warn("Hassium: PacketDecoder/Encoder not ready after retries, ZSTD install aborted");
                    return;
                }
                remaining--;
                channel.eventLoop().schedule(this, INSTALL_RETRY_MS, TimeUnit.MILLISECONDS);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            attempt.run();
        } else {
            channel.eventLoop().execute(attempt);
        }
    }

    /**
     * 反射获取 Connection.channel（加载器侧共用）。
     */
    public static Channel getConnectionChannel(Connection connection) {
        if (connection == null) {
            return null;
        }
        try {
            Field channelField = Connection.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            return (Channel) channelField.get(connection);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to get channel from connection", e);
            return null;
        }
    }

    /**
     * 标记下一帧跳过管线压缩（须在即将写出聚合包之前、同一 EventLoop 上调用更佳）。
     */
    public static void markSkipNextPipelineCompression(Connection connection) {
        Channel channel = getConnectionChannel(connection);
        if (channel != null) {
            channel.attr(HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION).set(true);
        }
    }

    private static void addHandlerBefore(ChannelPipeline pipeline, String baseName, String newName, ChannelHandler handler) {
        if (pipeline.get(baseName) != null) {
            pipeline.addBefore(baseName, newName, handler);
        } else {
            pipeline.addLast(newName, handler);
            LOGGER.warn("Handler '{}' not found, added '{}' to end of pipeline", baseName, newName);
        }
    }

    private static void removeHandlerSafely(ChannelPipeline pipeline, String name) {
        try {
            if (pipeline.get(name) != null) {
                pipeline.remove(name);
                LOGGER.debug("Removed handler: {}", name);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to remove handler '{}': {}", name, e.getMessage());
        }
    }

    /**
     * @param defaultName 找不到时返回值；传 null 表示找不到返回 null
     */
    private static String findHandlerNameByType(ChannelPipeline pipeline, Class<?> handlerClass, String defaultName) {
        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            if (handlerClass.isInstance(entry.getValue())) {
                return entry.getKey();
            }
        }
        return defaultName;
    }

    public static void switchToZlib(Channel channel, int threshold) {
        if (channel == null) {
            return;
        }
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> switchToZlib(channel, threshold));
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();

        if (pipeline.get(DECOMPRESS_HANDLER_NAME) != null) {
            pipeline.remove(DECOMPRESS_HANDLER_NAME);
        }
        if (pipeline.get(COMPRESS_HANDLER_NAME) != null) {
            pipeline.remove(COMPRESS_HANDLER_NAME);
        }

        String decoderName = findHandlerNameByType(pipeline, PacketDecoder.class, "decoder");
        String encoderName = findHandlerNameByType(pipeline, PacketEncoder.class, "encoder");
        addHandlerBefore(pipeline, decoderName, DECOMPRESS_HANDLER_NAME,
                new CompressionDecoder(threshold, true));
        addHandlerBefore(pipeline, encoderName, COMPRESS_HANDLER_NAME,
                new CompressionEncoder(threshold));

        LOGGER.info("Switched back to Zlib packet compression (threshold={})", threshold);
    }

    public static boolean isZstdInstalled(Channel channel) {
        if (channel == null) {
            return false;
        }
        ChannelPipeline pipeline = channel.pipeline();
        return pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder;
    }

    /**
     * 暂停出站压缩（阈值抬到极大）：后续帧走 {@code VarInt(0)+明文}。
     * <p>
     * Zlib / ZSTD 解码器都能吃这种未压缩帧，用于握手后、双方切 ZSTD 之前的安全窗口，
     * 避免一边已切算法、另一边仍按旧算法解压。
     */
    public static void pauseOutboundCompression(Channel channel) {
        setOutboundCompressionThreshold(channel, Integer.MAX_VALUE);
    }

    /**
     * 设置出站压缩阈值（Zlib {@link CompressionEncoder} 或 ZSTD 编码器均可）。
     */
    public static void setOutboundCompressionThreshold(Channel channel, int threshold) {
        if (channel == null) {
            return;
        }
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> setOutboundCompressionThreshold(channel, threshold));
            return;
        }
        ChannelHandler compress = channel.pipeline().get(COMPRESS_HANDLER_NAME);
        if (compress instanceof CompressionEncoder encoder) {
            encoder.setThreshold(threshold);
            LOGGER.info("Paused/updated Zlib outbound compression threshold={}", threshold);
        } else if (compress instanceof ZstdContextEncoder encoder) {
            encoder.setThreshold(threshold);
            LOGGER.info("Updated ZSTD outbound compression threshold={}", threshold);
        } else {
            LOGGER.debug("No compress handler to update threshold (pipeline={})", channel.pipeline().names());
        }
    }

    public static int getCurrentThreshold(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder encoder) {
            return encoder.getThreshold();
        } else if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof CompressionEncoder) {
            return 256;
        }

        return -1;
    }

    public static int getCurrentLevel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder encoder) {
            return encoder.getCompressionLevel();
        }

        return -1;
    }
}
