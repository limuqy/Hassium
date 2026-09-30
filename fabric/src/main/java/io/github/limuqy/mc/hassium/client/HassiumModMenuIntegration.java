package io.github.limuqy.mc.hassium.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod Menu 入口：全版本走 Cloth（无需 Configured / FCAP）。
 * Cloth Config 为可选依赖，未安装时尝试打开配置屏会捕获异常。
 */
public class HassiumModMenuIntegration implements ModMenuApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ModMenu");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        LOGGER.info("Hassium: ModMenu getModConfigScreenFactory called");
        return parent -> {
            try {
                return openConfig(parent);
            } catch (NoClassDefFoundError e) {
                LOGGER.warn("Hassium: Cloth Config not available, cannot open config screen");
                return null;
            }
        };
    }

    private static Screen openConfig(Screen parent) {
        // 26.x：cloth-config 暂无对应构建，cloth-ui 未编入；cloth 构建就绪后恢复
        // HassiumClothConfigScreen.create(parent)（历史见 git 与 docs/mc26-upgrade-plan.md）。
        return null;
    }
}
