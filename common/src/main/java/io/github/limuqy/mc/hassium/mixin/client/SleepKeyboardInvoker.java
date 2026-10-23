package io.github.limuqy.mc.hassium.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(net.minecraft.client.KeyboardHandler.class)
public interface SleepKeyboardInvoker {
#if MC_VER >= MC_1_21_9
    @Invoker("keyPress") void hassium$sleepKeyPress(long window,int action,net.minecraft.client.input.KeyEvent event);
#endif
}
