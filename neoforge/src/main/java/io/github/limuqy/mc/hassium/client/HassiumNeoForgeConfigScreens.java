package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

#if MC_VER < MC_1_20_2
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
#elif MC_VER < MC_1_20_5
import net.neoforged.neoforge.client.ConfigScreenHandler;
import net.neoforged.fml.ModList;
#else
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
#endif

/**
 * 注册模组列表「配置」按钮，打开 Cloth 配置屏。
 * <p>
 * 未注册时按钮为灰色；Configured 可选，不再作为硬依赖。
 */
public final class HassiumNeoForgeConfigScreens {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeConfig");

    private HassiumNeoForgeConfigScreens() {
    }

    public static void register() {
        ModList.get().getModContainerById(Constants.MOD_ID).ifPresentOrElse(container -> {
#if MC_VER < MC_1_20_5
            Supplier<ConfigScreenHandler.ConfigScreenFactory> supplier = () ->
                    new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> HassiumClothConfigScreen.create(parent));
            container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, supplier);
#else
            IConfigScreenFactory factory = (a, parent) -> HassiumClothConfigScreen.create(parent);
            container.registerExtensionPoint(IConfigScreenFactory.class, factory);
#endif
            LOGGER.info("Hassium: NeoForge 配置屏已注册（Cloth）");
        }, () -> LOGGER.warn("Hassium: 未找到模组容器，跳过配置屏注册"));
    }
}
