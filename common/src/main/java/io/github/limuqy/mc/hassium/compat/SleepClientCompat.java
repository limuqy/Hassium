package io.github.limuqy.mc.hassium.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class SleepClientCompat {
    private SleepClientCompat() {}
    /** Development scenario driver; sleep is only negotiated on the validated anchors. */
    public static void pressButton(net.minecraft.client.gui.components.Button button) {
#if MC_VER < MC_1_21_2
        button.onPress();
#else
        throw new UnsupportedOperationException("Sleep UI is not validated on this anchor");
#endif
    }
    public static void pick() {
#if MC_VER < MC_1_21_2
        Minecraft.getInstance().gameRenderer.pick(1.0f);
#endif
    }
    public static void pressEscape() {
#if MC_VER < MC_1_21_2
        var mc=Minecraft.getInstance();
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,org.lwjgl.glfw.GLFW.GLFW_PRESS,0);
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,org.lwjgl.glfw.GLFW.GLFW_RELEASE,0);
#endif
    }
    public static void renderStatus() {
#if MC_VER < MC_1_21_2
        Minecraft mc=Minecraft.getInstance();
        if(!io.github.limuqy.mc.hassium.client.sleep.SleepClient.sleeping() || mc.screen!=null) return;
        var graphics=new net.minecraft.client.gui.GuiGraphics(mc,mc.renderBuffers().bufferSource());
        int width=mc.getWindow().getGuiScaledWidth();
        String key="hassium.sleep.overlay";
        graphics.drawCenteredString(mc.font,Component.translatable(key),width/2,6,0xffd480);
        graphics.flush();
#endif
    }
}
