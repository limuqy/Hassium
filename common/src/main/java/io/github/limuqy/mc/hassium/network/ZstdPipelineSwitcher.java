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
 * 支持四种模式：
 * 1. 基础模式：使用无状态的 Zstd.compress/decompress
 * 2. 上下文模式：使用 ZstdCompressCtx/ZstdDecompressCtx（借鉴 NEB，提升压缩率）
 * 3. 聚合模式：包聚合 + 上下文压缩（最高压缩率）
 * 4. 紧凑包头模式：用 VarInt 索引替换 ResourceLocation 字符串
 */
public class ZstdPipelineSwitcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PipelineSwitcher");

    /**
     * Handler 名称常量（与原版一致）
     */
    private static final String DECOMPRESS_HANDLER_NAME = "decompress";
    private static final String COMPRESS_HANDLER_NAME = "compress";
    private static final String COMPACT_HEADER_HANDLER_NAME = "compact_header";

    /**
     * 切换到 ZSTD 压缩（根据配置自动选择模式）
     *
     * @param channel   Netty 通道
     * @param threshold 压缩阈值
     * @param level     ZSTD 压缩等级
     */
    public static void switchToZstd(Channel channel, int threshold, int level) {
        ChannelPipeline pipeline = channel.pipeline();

        // 移除原版 Handler（如果存在）
        // 使用安全的移除方式，避免 Handler 名称不匹配导致的问题
        removeHandlerSafely(pipeline, DECOMPRESS_HANDLER_NAME);
        removeHandlerSafely(pipeline, COMPRESS_HANDLER_NAME);
        removeHandlerSafely(pipeline, COMPACT_HEADER_HANDLER_NAME);

        // 记录当前 Pipeline 状态（调试用）
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Pipeline after removing old handlers: {}", pipeline.names());
        }

        // 获取配置
        HassiumConfigService config = HassiumConfigService.getInstance();
        boolean useContext = config.isUseContextCompression();
        boolean magicless = config.isMagiclessZstd();
        boolean useAggregation = config.isPacketAggregationEnabled();

        // 根据配置选择编码器/解码器
        final String mode;
        if (useAggregation && useContext) {
            // 聚合模式：包聚合 + 上下文压缩（最高压缩率）
            mode = "aggregated";
            PacketAggregator aggregator = new PacketAggregator(
                    config.getAggregationMinBatchSize(),
                    config.getAggregationMaxWaitTimeMs(),
                    config.getAggregationMaxSize()
            );
            addHandlerBefore(pipeline, "decoder", DECOMPRESS_HANDLER_NAME,
                    new AggregatedZstdDecoder(threshold, true, magicless));
            addHandlerBefore(pipeline, "encoder", COMPRESS_HANDLER_NAME,
                    new AggregatedZstdEncoder(threshold, level, magicless, aggregator));
        } else if (useContext) {
            // 上下文模式：使用 ZstdCompressCtx（借鉴 NEB，利用历史窗口状态）
            mode = "context";
            addHandlerBefore(pipeline, "decoder", DECOMPRESS_HANDLER_NAME,
                    new ZstdContextDecoder(threshold, true, magicless));
            addHandlerBefore(pipeline, "encoder", COMPRESS_HANDLER_NAME,
                    new ZstdContextEncoder(threshold, level, magicless));
        } else {
            // 基础模式：使用无状态的 Zstd.compress
            mode = "basic";
            addHandlerBefore(pipeline, "decoder", DECOMPRESS_HANDLER_NAME,
                    new ZstdPacketDecoder(threshold, true));
            addHandlerBefore(pipeline, "encoder", COMPRESS_HANDLER_NAME,
                    new ZstdPacketEncoder(threshold, level));
        }

        // 紧凑包头已在聚合包内部实现（通过 AggregatedSubPacket + CompactHeaderCodec），
        // 无需在 Pipeline 层安装独立的 CompactPacketEncoder。
        boolean compactHeader = config.isCompactHeaderEnabled();
        LOGGER.info("Installed ZSTD pipeline (mode={}, level={}, threshold={}, magicless={}, compactHeader={})",
                mode, level, threshold, magicless, compactHeader);
        if (compactHeader) {
            LOGGER.debug("Compact header enabled inside aggregated packets via CompactHeaderCodec");
        }

        // 记录最终 Pipeline 状态（调试用）
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

        // 移除紧凑包头处理器（如果存在）
        if (pipeline.get(COMPACT_HEADER_HANDLER_NAME) != null) {
            pipeline.remove(COMPACT_HEADER_HANDLER_NAME);
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
        return pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdPacketEncoder
                || pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder
                || pipeline.get(COMPRESS_HANDLER_NAME) instanceof AggregatedZstdEncoder;
    }

    /**
     * 检查当前是否使用上下文模式
     */
    public static boolean isContextMode(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        return pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder
                || pipeline.get(COMPRESS_HANDLER_NAME) instanceof AggregatedZstdEncoder;
    }

    /**
     * 检查当前是否使用聚合模式
     */
    public static boolean isAggregationMode(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        return pipeline.get(COMPRESS_HANDLER_NAME) instanceof AggregatedZstdEncoder;
    }

    /**
     * 获取当前压缩阈值
     */
    public static int getCurrentThreshold(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdPacketEncoder encoder) {
            return encoder.getThreshold();
        } else if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder encoder) {
            return encoder.getThreshold();
        } else if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof AggregatedZstdEncoder encoder) {
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

        if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdPacketEncoder encoder) {
            return encoder.getCompressionLevel();
        } else if (pipeline.get(COMPRESS_HANDLER_NAME) instanceof ZstdContextEncoder encoder) {
            return encoder.getCompressionLevel();
        }

        return -1; // 未安装压缩或使用 Zlib
    }
}
