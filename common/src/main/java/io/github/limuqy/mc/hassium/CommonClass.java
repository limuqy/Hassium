package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import io.github.limuqy.mc.hassium.compat.NetworkCapability;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.platform.Services;

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
     * 由各加载器的入口点调用。调用前应已完成 ConfigSpec 注册。
     */
    public static void init() {
        Constants.LOG.info("Initializing Hassium v{} on {} ({})",
                Constants.CURRENT_STORAGE_FORMAT_VERSION,
                Services.PLATFORM.getPlatformName(),
                Services.PLATFORM.getEnvironmentName());

        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            Constants.LOG.info("Running in development environment");
        }

        try {
            HassiumCompression.initialize();
        } catch (Exception e) {
            Constants.LOG.error("Failed to initialize compression system", e);
        }

        try {
            HassiumConfigService configService = HassiumConfigService.getInstance();
            // Fabric 已 loadFromToml；Forge/NeoForge 再从 Spec 同步
            if (!configService.isTomlBackend()) {
                configService.syncFromSpec();
            }
            NetworkStats.setEnabled(configService.isMetricsEnabled());

            if (!NetworkCapability.isCustomChannelFullySupported()) {
                configService.setNetworkCompressionEnabled(false);
                Constants.LOG.warn(NetworkCapability.unsupportedReason());
            }
        } catch (Exception e) {
            Constants.LOG.error("Failed to load configuration", e);
        }

        Constants.LOG.info("Hassium common initialization complete");
    }
}
