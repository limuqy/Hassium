package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 注册模组列表「配置」按钮，打开 Cloth 配置屏。
 * Cloth Config 为可选依赖，未安装时跳过注册。
 */
public final class HassiumForgeConfigScreens {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ForgeConfig");

    private HassiumForgeConfigScreens() {
    }

    public static void register() {
        if (!isClothAvailable()) {
            LOGGER.info("Hassium: Cloth Config 未安装，跳过配置屏注册");
            return;
        }
        ModList.get().getModContainerById(Constants.MOD_ID).ifPresentOrElse(container -> {
            Supplier<ConfigScreenHandler.ConfigScreenFactory> supplier = () ->
                    new ConfigScreenHandler.ConfigScreenFactory(
                            // 反射调用 HassiumClothConfigScreen.create(parent)：
                            // 当 forge 子项目未引 cloth-config-forge（如 1.21.5+ cloth 18 上游停发 forge 变体）
                            // 时，cloth-ui 源码不会纳入 forge 子项目，直接引用会导致编译失败。这里改用反射，
                            // 使本类在无 cloth 的情况下也能编译；运行时 isClothAvailable 守门避免误调用。
                            (minecraft, parent) -> createClothScreenReflect(parent));
            container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, supplier);
            LOGGER.info("Hassium: Forge 配置屏已注册（Cloth）");
        }, () -> LOGGER.warn("Hassium: 未找到模组容器，跳过配置屏注册"));
    }

    /** 反射调用 HassiumClothConfigScreen.create(parent)。失败时返回 null，调用方需确保 isClothAvailable 守门。 */
    @SuppressWarnings("unused")
    private static net.minecraft.client.gui.screens.Screen createClothScreenReflect(net.minecraft.client.gui.screens.Screen parent) {
        try {
            Class<?> cls = Class.forName("io.github.limuqy.mc.hassium.client.HassiumClothConfigScreen");
            java.lang.reflect.Method m = cls.getMethod("create", net.minecraft.client.gui.screens.Screen.class);
            return (net.minecraft.client.gui.screens.Screen) m.invoke(null, parent);
        } catch (Throwable t) {
            LOGGER.error("Hassium: 反射创建 HassiumClothConfigScreen 失败", t);
            return parent;
        }
    }

    private static boolean isClothAvailable() {
        try {
            // 真实 cloth 包名是 me.shedaniel.clothconfig2.api（非 me.shedaniel.cloth.config2）。
            Class.forName("me.shedaniel.clothconfig2.api.ConfigBuilder");
            // 还要确保自身 cloth UI 源码能加载（forge 1.21.5+ 未纳入 cloth-ui，调用即失败 -> false）。
            Class.forName("io.github.limuqy.mc.hassium.client.HassiumClothConfigScreen", false,
                    HassiumForgeConfigScreens.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }
}
