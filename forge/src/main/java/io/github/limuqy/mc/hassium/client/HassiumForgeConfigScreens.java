package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 注册模组列表「配置」按钮，打开 Cloth 配置屏。
 */
public final class HassiumForgeConfigScreens {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ForgeConfig");

    private HassiumForgeConfigScreens() {
    }

    public static void register() {
        ModList.get().getModContainerById(Constants.MOD_ID).ifPresentOrElse(container -> {
            Supplier<ConfigScreenHandler.ConfigScreenFactory> supplier = () ->
                    new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> HassiumClothConfigScreen.create(parent));
            container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, supplier);
            LOGGER.info("Hassium: Forge 配置屏已注册（Cloth）");
        }, () -> LOGGER.warn("Hassium: 未找到模组容器，跳过配置屏注册"));
    }
}
