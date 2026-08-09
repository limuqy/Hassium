package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端区块缓存：影子端光照更新桥梁（T2）。
 * <p>
 * 影子服务端（客户端进程内的 world 后端）引擎每完成一个 section 的光照计算，
 * 写数据层前会调用 {@code ServerChunkCache.onLightUpdate(LightLayer, SectionPos)}——
 * HEAD 拦截转发到 {@link ShadowLightCompute#collectLightUpdate}（light 线程入口，
 * ConcurrentHashMap + synchronized(mask) 线程安全收集，绝对 sectionY）；
 * 客户端主线程帧尾 {@code drainLightMasks} 攒批打包入回传队列。
 * <p>
 * 门控：仅影子服务端上下文生效（{@link RuntimeServerContext#isShadowServerContext()}）；
 * 专用服务器 / 普通集成服务器不受影响——onLightUpdate 路径零额外开销。
 * <p>
 * 1.20.1 / 1.21.11 方法签名一致（已双版本验证），零 #if。
 */
@Mixin(net.minecraft.server.level.ServerChunkCache.class)
public class MixinServerChunkCache {

    /**
     * 影子服务端：光照 section 计算完成 → 收集（绝对 sectionY）。
     */
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void hassium$onLightUpdate(LightLayer layer, SectionPos sectionPos, CallbackInfo ci) {
        if (!RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        ShadowLightCompute.collectLightUpdate(layer, sectionPos);
    }
}
