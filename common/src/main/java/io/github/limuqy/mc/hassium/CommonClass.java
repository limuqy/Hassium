package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.platform.Services;

import java.nio.file.Path;

/**
 * Hassium 模组的公共初始化类
 * <p>
 * 这个类在 common 项目中，被所有支持的加载器共享。这里编写的代码只能访问原版代码库、
 * 原版使用的库，以及提供通用兼容二进制文件的可选第三方库。
 * 这意味着 common 代码不能直接使用加载器特定的概念（如 Forge 事件），
 * 但它将与所有支持的模组加载器兼容。
 */
public class CommonClass {

    /**
     * 模组初始化入口
     * <p>
     * 由各加载器的入口点调用。
     */
    public static void init() {
        Constants.LOG.info("Initializing Hassium v{} on {} ({})",
                Constants.CURRENT_STORAGE_FORMAT_VERSION,
                Services.PLATFORM.getPlatformName(),
                Services.PLATFORM.getEnvironmentName());

        // 检查是否在开发环境
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            Constants.LOG.info("Running in development environment");
        }

        // 初始化压缩系统（加载字典和注册编解码器）
        try {
            HassiumCompression.initialize();
        } catch (Exception e) {
            Constants.LOG.error("Failed to initialize compression system", e);
        }

        // 设置配置目录并加载配置
        try {
            io.github.limuqy.mc.hassium.config.HassiumConfigService configService =
                io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance();
            // 配置目录：config/hassium/
            Path configDir = Services.PLATFORM.getConfigDirectory().resolve(Constants.MOD_ID);
            configService.setConfigDir(configDir);
            configService.setPhysicalClient(Services.PLATFORM.isPhysicalClient());
            configService.loadConfig();

            // 同步指标开关到 NetworkStats
            NetworkStats.setEnabled(configService.isMetricsEnabled());
        } catch (Exception e) {
            Constants.LOG.error("Failed to load configuration", e);
        }

        Constants.LOG.info("Hassium common initialization complete");
    }
}
