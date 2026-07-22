package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
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

    /**
     * Handler 名称常量（与原版一致）
     */
    private static final String DECOMPRESS_HANDLER_NAME = "decompress";
    private static final String COMPRESS_HANDLER_NAME = "compress";

    /**
     * 切换到 ZSTD 压缩（使用上下文编码器，利用历史窗口提升压缩率）
     *
     * @param channel   Netty 通道
     * @param threshold 压缩阈值
     * @param level     ZSTD 压缩等级
     */
    public static void switchToZstd(Channel channel, int threshold, int level) {
        ChannelPipeline pipeline = channel.pipeline();

        // 移除旧的压缩 Handler（如果存在）
        removeHandlerSafely(pipeline, DECOMPRESS_HANDLER_NAME);
        removeHandlerSafely(pipeline, COMPRESS_HANDLER_NAME);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Pipeline after removing old handlers: {}", pipeline.names());
        }

        // 获取配置
        HassiumConfigService config = HassiumConfigService.getInstance();
        boolean magicless = config.isMagiclessZstd();

        // 使用上下文编码器/解码器（管线 ZSTD 压缩所有包）
        addHandlerBefore(pipeline, "decoder", DECOMPRESS_HANDLER_NAME,
                new ZstdContextDecoder(threshold, true, magicless));
        addHandlerBefore(pipeline, "encoder", COMPRESS_HANDLER_NAME,
                new ZstdContextEncoder(threshold, level, magicless));

        LOGGER.info("Installed ZSTD pipeline (level={}, threshold={}, magicless={})",
                level, threshold, magicless);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Pipeline after installing ZSTD handlers: {}", pipeline.names());
        }
    }

    /**
     * 安全地在指定 Handler 之前添加新 Handler
     * <p>
     * 如果目标 Handler 不存在，则添加到 Pipeline 末尾
     */
    private static void addHandlerBefore(ChannelPipeline pipeline, String baseName, String newName, ChannelHandler handler) {
        if (pipeline.get(baseName) != null) {
            pipeline.addBefore(baseName, newName, handler);
        } else {
            // 如果目标 Handler 不存在，尝试添加到末尾
            pipeline.addLast(newName, handler);
            LOGGER.warn("Handler '{}' not found, added '{}' to end of pipeline", baseName, newName);
        }
    }

    /**
     * 安全地移除指定名称的 Handler
     * <p>
     * 如果 Handler 不存在或移除失败，记录警告但不抛出异常
     */
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
     * 切换回 Zlib 压缩（降级）
     *
     * @param channel   Netty 通道
     * @param threshold 压缩阈值
     */
    public static void switchToZlib(Channel channel, int threshold) {
        ChannelPipeline pipeline = channel.pipeline();

        // 移除 ZSTD Handler（如果存在）
        if (pipeline.get(DECOMPRESS_HANDLER_NAME) != null) {
            pipeline.remove(DECOMPRESS_HANDLER_NAME);
        }
        if (pipeline.get(COMPRESS_HANDLER_NAME) != null) {
            pipeline.remove(COMPRESS_HANDLER_NAME);
        }

        // 重新安装原版 Zlib Handler
        pipeline.addBefore("decoder", DECOMPRESS_HANDLER_NAME,
                new CompressionDecoder(threshold, true));
        pipeline.addBefore("encoder", COMPRESS_HANDLER_NAME,
                new CompressionEncoder(threshold));

        LOGGER.info("Switched back to Zlib packet compression (threshold={})", threshold);
    }

    /**
     * 检查当前是否使用 ZSTD 压缩
     */
    public static boolean isZstdInstalled(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        return pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder;
    }

    /**
     * 获取当前压缩阈值
     */
    public static int getCurrentThreshold(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder encoder) {
            return encoder.getThreshold();
        } else if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof CompressionEncoder) {
            // 原版 CompressionEncoder 没有 getter，返回默认值
            return 256;
        }

        return -1; // 未安装压缩
    }

    /**
     * 获取当前压缩级别
     */
    public static int getCurrentLevel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder encoder) {
            return encoder.getCompressionLevel();
        }

        return -1; // 未安装压缩或使用 Zlib
    }
}
