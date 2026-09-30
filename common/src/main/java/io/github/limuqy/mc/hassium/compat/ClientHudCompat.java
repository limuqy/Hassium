package io.github.limuqy.mc.hassium.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 客户端 HUD/屏幕跨版本收口。
 * <p>
 * 屏幕状态：26.1.2 在 {@code Minecraft.screen}（+ {@code Minecraft.setScreen}）；
 * 26.2+ 迁入 {@code Gui}（{@code gui.screen()} / {@code gui.setScreen}）。
 * 聊天显示：26.1.2 {@code gui.getChat()}；26.2+ 迁入 {@code gui.hud.getChat()}。
 * 业务代码一律走本门面，禁止散落 {@code #if}（见 hassium-manifold skill）。
 */
public final class ClientHudCompat {

    private ClientHudCompat() {
    }

    /** 当前打开的屏幕（无屏幕返回 null）。 */
    public static Screen currentScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
#if MC_VER >= MC_26_2
        return mc.gui != null ? mc.gui.screen() : null;
#else
        return mc.screen;
#endif
    }

    /** 聊天栏系统消息（无 HUD 时静默丢弃）。 */
    public static void sendSystemMessage(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }
#if MC_VER >= MC_26_2
        mc.gui.hud.getChat().addClientSystemMessage(message);
#else
        mc.gui.getChat().addClientSystemMessage(message);
#endif
    }
}
