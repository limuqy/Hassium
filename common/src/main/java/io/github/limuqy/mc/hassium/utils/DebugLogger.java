package io.github.limuqy.mc.hassium.utils;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调试日志工具类
 * 根据配置控制不同类型的日志输出
 */
public class DebugLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium");

    /**
     * 日志类型枚举
     */
    public enum LogType {
        METADATA,       // 元数据相关 (CLIENT_METADATA, COMPARE_METADATA, APPLY_METADATA)
        DISPATCHER,     // 主线程调度器 (MAIN_DISPATCHER)
        ASYNC,          // 异步任务 (ASYNC)
        COMPRESSION,    // 压缩/解压 (HANDLE_COMPRESSED)
        CHUNK_APPLY,    // 区块应用 (APPLY_CHUNK)
        NETWORK,        // 网络传输
        CACHE           // 缓存操作
    }

    /**
     * 检查指定类型的日志是否启用
     */
    public static boolean isEnabled(LogType type) {
        try {
            HassiumConfigService configService = HassiumConfigService.getInstance();
            if (!configService.isConfigLoaded()) {
                return false;
            }
            HassiumConfig.DebugConfig debug = configService.getConfig().debug();
            return switch (type) {
                case METADATA -> debug.metadataLogging();
                case DISPATCHER -> debug.dispatcherLogging();
                case ASYNC -> debug.asyncLogging();
                case COMPRESSION -> debug.compressionLogging();
                case CHUNK_APPLY -> debug.chunkApplyLogging();
                case NETWORK -> debug.networkLogging();
                case CACHE -> debug.cacheLogging();
            };
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 条件日志输出 - INFO 级别
     */
    public static void info(LogType type, String format, Object... args) {
        if (isEnabled(type)) {
            LOGGER.info(format, args);
        }
    }

    /**
     * 条件日志输出 - DEBUG 级别
     */
    public static void debug(LogType type, String format, Object... args) {
        if (isEnabled(type)) {
            LOGGER.debug(format, args);
        }
    }

    /**
     * 条件日志输出 - WARN 级别
     */
    public static void warn(LogType type, String format, Object... args) {
        if (isEnabled(type)) {
            LOGGER.warn(format, args);
        }
    }

    /**
     * 条件日志输出 - ERROR 级别（总是输出）
     */
    public static void error(String format, Object... args) {
        LOGGER.error(format, args);
    }

    /**
     * 条件日志输出 - ERROR 级别（带异常）
     */
    public static void error(String format, Throwable t, Object... args) {
        LOGGER.error(format, args, t);
    }
}
