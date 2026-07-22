package io.github.limuqy.mc.hassium.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * ClientboundLightUpdatePacket 的辅助类。
 * <p>
 * 由于字段名在不同 MC 版本中不同，使用反射而非 accessor。
 * 字段提取逻辑在 {@link io.github.limuqy.mc.hassium.mixin.MixinChunkHolder} 中实现。
 */
@Mixin(net.minecraft.network.protocol.game.ClientboundLightUpdatePacket.class)
public class LightUpdatePacketAccessor {
    // 使用反射访问字段，避免跨版本 accessor 兼容问题
}
