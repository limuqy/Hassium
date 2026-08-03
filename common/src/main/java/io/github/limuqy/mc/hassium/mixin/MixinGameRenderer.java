package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * 注入点选在 {@code Screen.renderWithTooltip} 自身（@Inject HEAD）而非 GameRenderer.render
 * 内的调用点（@Redirect）：forge/neoforge 的 GameRenderer.render 被 patch 为
 * {@code ForgeHooksClient.drawScreen}/{@code ClientHooks.drawScreen}（内部仍调用
 * {@code renderWithTooltip}），调用点随加载器不同，目标方法对所有加载器统一。
 * <p>
 * 全版本生效；目标方法名在 1.21.9 改名，按段分流。mixins.json 无条件登记。
 */
@Mixin(Screen.class)
public class MixinGameRenderer {

#if MC_VER < MC_1_21_9
    @Inject(method = "renderWithTooltip", at = @At("HEAD"), cancellable = true)
#else
    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
#endif
    private void hassium$hideTransitionScreen(GuiGraphics guiGraphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo ci) {
        if (ClientFailoverIdentity.isRecoverySessionActive()
                && ((Object) this instanceof ConnectScreen
                    || (Object) this instanceof ProgressScreen
#if MC_VER < MC_1_21_9
                    // 1.21.9+ ReceivingLevelScreen 已移除（setLevel 的 Reason 一并删除）
                    || (Object) this instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
#endif
                    )) {
            // 过渡画面不绘制：冻结世界 + HUD 浮层保持
            ci.cancel();
        }
    }
}
