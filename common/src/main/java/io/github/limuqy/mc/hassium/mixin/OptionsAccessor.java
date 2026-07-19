package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Options} 字段访问器。
 * <p>
 * {@code serverRenderDistance} 由 {@code ClientboundSetChunkCacheRadiusPacket} 在 login 后写入，
 * 反映服务端 {@code view-distance}。原版为 private 字段，通过 Accessor 暴露给
 * {@link io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService} 用于超视渲染环带计算。
 */
@Mixin(Options.class)
public interface OptionsAccessor {

    /** 获取服务端视距（login 后由服务端 chunk cache radius 包写入；未登录为 0） */
    @Accessor("serverRenderDistance")
    int hassium$getServerRenderDistance();
}
