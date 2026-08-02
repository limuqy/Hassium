package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * L2 主控热切无缝化渲染遮挡：恢复会话期间（世界画面定格）过渡画面不绘制。
 * <p>
 * setScreen 已放行 ConnectScreen/ProgressScreen/ReceivingLevelScreen（Fabric
 * {@code ClientNetworkingImpl.getLoginConnection} 依赖 {@code mc.screen instanceof
 * ConnectScreen} 取回候选连接），vanilla 会叠加渲染这些画面——此处拦截
 * {@code Screen.renderWithTooltip}（1.21.9+ 为 {@code renderWithTooltipAndSubtitles}），
 * 过渡画面改为不绘制：GameRenderer 的世界段与 HUD 段（含 MixinGui 的「正在切换主控…」浮层）
 * 与 screen 无关总是渲染，画面保持冻结世界 + 浮层，无任何过渡画面残影。
 * <p>
 * 全版本生效；redirect 目标签名在 1.21.9 改名，按段分流。mixins.json 无条件登记。
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

#if MC_VER < MC_1_21_9
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
#else
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
#endif
    private void hassium$hideTransitionScreen(Screen screen, GuiGraphics guiGraphics,
                                              int mouseX, int mouseY, float partialTick) {
        if (ClientFailoverIdentity.isRecoverySessionActive()
                && (screen instanceof net.minecraft.client.gui.screens.ConnectScreen
                    || screen instanceof net.minecraft.client.gui.screens.ProgressScreen
#if MC_VER < MC_1_21_9
                    // 1.21.9+ ReceivingLevelScreen 已移除（setLevel 的 Reason 一并删除）
                    || screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
#endif
                    )) {
            // 过渡画面不绘制：冻结世界 + HUD 浮层保持
            return;
        }
#if MC_VER < MC_1_21_9
        screen.renderWithTooltip(guiGraphics, mouseX, mouseY, partialTick);
#else
        screen.renderWithTooltipAndSubtitles(guiGraphics, mouseX, mouseY, partialTick);
#endif
    }
}
