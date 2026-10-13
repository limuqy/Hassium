package io.github.limuqy.mc.hassium.mixin.server;

import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PacketDecoder.class)
public interface PacketDecoderProtocolAccess {
    @Accessor("protocolInfo") ProtocolInfo<?> hassium$getProtocolInfo();
}
