package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 解除 {@link Options#getEffectiveRenderDistance()} 的服务端钳制，
 * 返回 min(客户端 RD 滑块, {@code clientCache.maxRenderDistance})，从而扩大 ViewArea。
 * <p>
 * 门控：仅多人游戏 + clientCache.enabled + viewDistanceExtensionEnabled 时启用；
 * 单人游戏不启用（无缓存数据源，且单人 RD 不受 server 钳制）。
 * 视距外环带由 {@link io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService}
 * 从本地缓存回填，{@code renderOnly} 标记保证不参与模拟。
 */
@Mixin(Options.class)
public class MixinOptions {

    @Inject(method = "getEffectiveRenderDistance", cancellable = true, at = @At("HEAD"))
    private void hassium$unclampRenderDistance(CallbackInfoReturnable<Integer> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        // 单人游戏不启用（无缓存 + 无 server 钳制）
        if (mc.getSingleplayerServer() != null) {
            return;
        }
        if (mc.level == null || mc.getConnection() == null) {
            return;
        }

        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientCacheEnabled() || !cfg.isViewDistanceExtensionEnabled()) {
            return;
        }

        // min(滑块, maxRenderDistance)，与超视渲染环带 / Cache 半径一致
        int slider = ((Options) (Object) this).renderDistance().get();
        int max = cfg.getMaxRenderDistance();
        cir.setReturnValue(Math.max(2, Math.min(slider, max)));
    }
}
