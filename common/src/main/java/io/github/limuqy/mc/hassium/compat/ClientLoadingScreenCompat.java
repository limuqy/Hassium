package io.github.limuqy.mc.hassium.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 进服地形加载屏判定（跨版本类名收口）。
 * <p>
 * 1.20.1–1.21.8：{@code ReceivingLevelScreen}；1.21.9+ 并入 {@code LevelLoadingScreen}。
 * 业务代码不得散落 {@code #if}。
 */
public final class ClientLoadingScreenCompat {

    private ClientLoadingScreenCompat() {
    }

    public static boolean isTerrainLoadingScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
#if MC_VER < MC_1_21_9
        return screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen;
#else
        return screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
#endif
    }

    /** 当前客户端是否仍停在进服地形加载屏。 */
    public static boolean isVisible() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && isTerrainLoadingScreen(mc.screen);
    }
}
