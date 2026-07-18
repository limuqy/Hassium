package io.github.limuqy.mc.hassium.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 侧配置：Night Config 自管 toml（不依赖 Forge Config API Port）。
 * 物理客户端：{@code hassium-client.toml} + {@code hassium-common.toml}；
 * 专用服仅 {@code hassium-common.toml}。
 */
public final class FabricHassiumConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Config");

    private FabricHassiumConfig() {
    }

    public static void register() {
        HassiumConfigService.getInstance().loadFromToml();
        LOGGER.info("Hassium: Fabric Toml 配置已加载 (config/hassium/*)");
    }
}
