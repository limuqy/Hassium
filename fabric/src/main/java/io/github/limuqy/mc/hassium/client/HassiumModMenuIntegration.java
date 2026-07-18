package io.github.limuqy.mc.hassium.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/**
 * Mod Menu 入口：全版本走 Cloth（无需 Configured / FCAP）。
 */
public class HassiumModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> openConfig(parent);
    }

    private static Screen openConfig(Screen parent) {
        return HassiumClothConfigScreen.create(parent);
    }
}
