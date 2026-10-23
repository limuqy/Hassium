package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.sleep.SleepClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.KeyboardHandler.class)
public abstract class MixinSleepKeyboardHandler {
#if MC_VER < MC_1_21_9
    @Inject(method="keyPress",at=@At("HEAD"))
    private void hassium$sleepEscape(long window,int key,int scanCode,int action,int modifiers,CallbackInfo ci) {
        if(window==Minecraft.getInstance().getWindow().getWindow() && key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                && action==org.lwjgl.glfw.GLFW.GLFW_PRESS) SleepClient.wake();
    }
#else
    @Inject(method="keyPress",at=@At("HEAD"))
    private void hassium$sleepEscape(long window,int action,net.minecraft.client.input.KeyEvent event,CallbackInfo ci) {
        if(window==Minecraft.getInstance().getWindow().handle()
                && event.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                && action==org.lwjgl.glfw.GLFW.GLFW_PRESS) SleepClient.wake();
    }
#endif
}
