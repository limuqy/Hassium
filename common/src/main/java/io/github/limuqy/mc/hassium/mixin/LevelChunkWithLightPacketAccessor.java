package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 仅替换既有区块包的 light payload，保留原版或兼容 Mod 已改写的 chunk data。
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public interface LevelChunkWithLightPacketAccessor {

    @Mutable
    @Accessor("lightData")
    void hassium$setLightData(ClientboundLightUpdatePacketData lightData);
}
