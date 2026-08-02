package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * L2 主控热切定格浮层：恢复窗口内（世界画面定格）绘制半透明遮罩 + 「正在切换主控…」提示。
 * <p>
 * 全版本生效；{@code Gui.render} 签名在 1.21.1 由 {@code (GuiGraphics,float)} 改为
 * {@code (GuiGraphics,DeltaTracker)}，按段分流。mixins.json 无条件登记。
 */
@Mixin(Gui.class)
public class MixinGui {

    @Shadow
    private Minecraft minecraft;

#if MC_VER < MC_1_21_1
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("TAIL"))
    private void hassium$renderFailoverOverlay(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
#else
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void hassium$renderFailoverOverlay(GuiGraphics guiGraphics, net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
#endif
        // 无感切换（recoveryFreeze=false）不绘制任何切换 UI：用户体感为延迟变高+回退
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isRecoveryFreeze()
                || !io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecovering()) {
            return;
        }
        int w = guiGraphics.guiWidth();
        int h = guiGraphics.guiHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 半透明黑全屏遮罩 + 居中白字
        guiGraphics.fill(0, 0, w, h, 0x66000000);
        if (minecraft.font != null) {
            guiGraphics.drawCenteredString(
                    minecraft.font,
                    Component.literal("正在切换主控…"),
                    w / 2,
                    h / 2,
                    0xFFFFFF);
        }
    }
}
