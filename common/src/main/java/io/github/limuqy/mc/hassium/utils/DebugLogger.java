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
        COMPRESSION,    // 压缩/解压（ZSTD 管线）
        CHUNK_APPLY,    // 区块应用 (APPLY_CHUNK / CHUNK_PROBE 非 light)
        NETWORK,        // 网络传输
        CACHE,          // 缓存操作
        LIGHT           // 光照调试总开关：算光链路日志 + 光包落地/引擎探针（debug.lightLogging，别名 debug.lightVerify）
    }

    /**
     * review-fix: T8-26: 热路径开销——isEnabled 原实现每调用走 getInstance()+isConfigLoaded()+
     * getConfig()（读锁）+switch 取 debug 布尔位；MainThreadDispatcher 每任务每帧 3+ 条
     * info 日志，日志关闭时判断成本仍在。缓存「配置实例身份（=配置变更版本号）+ 位图」，
     * 配置加载（applyLoaded 替换 config 实例）后首次调用自动重算；两字段 volatile 发布，
     * 竞态仅致一次性陈旧位，下次调用自愈。
     */
    private static volatile HassiumConfig cachedConfig;
    private static volatile int cachedEnabledBits;

    /**
     * 检查指定类型的日志是否启用
     */
    public static boolean isEnabled(LogType type) {
        try {
            HassiumConfigService configService = HassiumConfigService.getInstance();
            if (!configService.isConfigLoaded()) {
                return false;
            }
            // 冒烟默认打开 CHUNK_APPLY，便于对照 [CHUNK_APPLY] 与 [CHUNK_MESH]
            if (type == LogType.CHUNK_APPLY
                    && Boolean.parseBoolean(System.getProperty("hassium.smokeTest", "false"))) {
                return true;
            }
            HassiumConfig config = configService.getConfig();
            int bits = cachedEnabledBits;
            if (config != cachedConfig) {
                // 配置实例被替换（reload）→ 重算缓存位（配置变更版本号 = 实例身份）
                bits = computeEnabledBits(config);
                cachedConfig = config;
                cachedEnabledBits = bits;
            }
            return (bits & (1 << type.ordinal())) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 一次性把 debug 位打包成 int（按 LogType.ordinal() 置位）。 */
    private static int computeEnabledBits(HassiumConfig config) {
        HassiumConfig.DebugConfig debug = config.debug();
        int bits = 0;
        if (debug.metadataLogging()) bits |= 1 << LogType.METADATA.ordinal();
        if (debug.dispatcherLogging()) bits |= 1 << LogType.DISPATCHER.ordinal();
        if (debug.asyncLogging()) bits |= 1 << LogType.ASYNC.ordinal();
        if (debug.compressionLogging()) bits |= 1 << LogType.COMPRESSION.ordinal();
        if (debug.chunkApplyLogging()) bits |= 1 << LogType.CHUNK_APPLY.ordinal();
        if (debug.networkLogging()) bits |= 1 << LogType.NETWORK.ordinal();
        if (debug.cacheLogging()) bits |= 1 << LogType.CACHE.ordinal();
        // 光照调试总开关：新键 debug.lightLogging，或历史别名 debug.lightVerify（两者任一开即生效）。
        if (debug.lightLogging() || debug.lightVerify()) bits |= 1 << LogType.LIGHT.ordinal();
        return bits;
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
