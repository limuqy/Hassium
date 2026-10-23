package io.github.limuqy.mc.hassium.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class SleepClientCompat {
    private SleepClientCompat() {}
    /** Development scenario driver. */
    public static void pressButton(net.minecraft.client.gui.components.Button button) {
#if MC_VER < MC_1_21_9
        button.onPress();
#else
        button.onPress(new net.minecraft.client.input.KeyEvent(0,0,0));
#endif
    }
    public static void pick() {
        Minecraft.getInstance().gameRenderer.pick(1.0f);
    }
    public static void pressEscape() {
#if MC_VER < MC_1_21_9
        var mc=Minecraft.getInstance();
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,org.lwjgl.glfw.GLFW.GLFW_PRESS,0);
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,org.lwjgl.glfw.GLFW.GLFW_RELEASE,0);
#else
        var mc=Minecraft.getInstance();
        var keyboard=(io.github.limuqy.mc.hassium.mixin.client.SleepKeyboardInvoker)mc.keyboardHandler;
        var key=new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
        keyboard.hassium$sleepKeyPress(mc.getWindow().handle(),org.lwjgl.glfw.GLFW.GLFW_PRESS,key);
        keyboard.hassium$sleepKeyPress(mc.getWindow().handle(),org.lwjgl.glfw.GLFW.GLFW_RELEASE,key);
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
    public static void renderStatus(net.minecraft.client.gui.GuiGraphics graphics) {
        Minecraft mc=Minecraft.getInstance();
        if(!io.github.limuqy.mc.hassium.client.sleep.SleepClient.sleeping() || mc.screen!=null) return;
        graphics.drawCenteredString(mc.font,Component.translatable("hassium.sleep.overlay"),mc.getWindow().getGuiScaledWidth()/2,6,0xffd480);
    }
}
