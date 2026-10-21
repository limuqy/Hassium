package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.sleep.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.screens.PauseScreen.class)
public abstract class MixinSleepPauseScreen extends Screen {
    protected MixinSleepPauseScreen(Component title) { super(title); }
#if MC_VER < MC_1_21_2
    @Inject(method="init",at=@At("TAIL"))
    private void hassium$sleepButton(CallbackInfo ci) {
        if(!SleepClient.supported()) return;
        addRenderableWidget(Button.builder(Component.translatable("hassium.sleep.enter"),b->minecraft.setScreen(new SleepSetupScreen((Screen)(Object)this)))
                .bounds(Math.max(4,width-114),6,108,20).build());
    }
#endif
}
