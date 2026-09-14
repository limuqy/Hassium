package io.github.limuqy.mc.hassium.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 侧配置：Night Config 自管 toml（不依赖 Forge Config API Port）。
 * 物理客户端：{@code hassium-client.toml} + {@code hassium-server.toml}
 * （后者供集成服务器/局域网读取，UI 不展示服务端键）；
 * 专用服：{@code hassium-server.toml}。
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
