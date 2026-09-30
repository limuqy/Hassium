package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.ClientLifecycleHelper;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gui Mixin：26.3 起当前屏幕状态从 {@code Minecraft.screen} 迁入 {@code Gui}，
 * {@code Minecraft.setScreen} 移除（{@code setScreenAndShow} / {@code gui.setScreen} 取代）。
 * <p>
 * T0b 诊断：加载屏消退时刻（原 MixinMinecraft 的 setScreen HEAD 注入迁移至此，
 * 覆盖 MouseHandler/KeyboardHandler 等绕过 setScreenAndShow 的 gui.setScreen 直调）。
 */
@Mixin(Gui.class)
public abstract class MixinGui {

    /**
     * T0b 诊断：加载屏消退时刻（加载屏被替换为非加载屏，含 setScreen(null)）。
     */
    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void hassium$onSetScreen(Screen screen, CallbackInfo ci) {
        Screen current = ((Gui) (Object) this).screen();
        if (current instanceof net.minecraft.client.gui.screens.ConnectScreen) {
            ClientLifecycleHelper.onConnectScreenDismissed(screen);
        }
        if (current instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
                && !(screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)) {
            io.github.limuqy.mc.hassium.utils.LoginTiming.onLoadingScreenDismissed();
        }
    }
}
