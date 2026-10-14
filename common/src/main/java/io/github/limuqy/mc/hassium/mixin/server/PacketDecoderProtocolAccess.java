package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.compat.EgressProtocol;
import io.netty.channel.Channel;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.*;

@Mixin(PacketDecoder.class)
public abstract class PacketDecoderProtocolAccess implements io.github.limuqy.mc.hassium.compat.EgressDecoderBinding {
#if MC_VER < MC_1_21_1
    @Shadow @Final private net.minecraft.network.protocol.PacketFlow flow;
#else
    @Shadow @Final private net.minecraft.network.ProtocolInfo<?> protocolInfo;
#endif
    @Unique private Object hassium$identity;
    @Unique private EgressProtocol hassium$binding;
    @Unique public EgressProtocol hassium$getProtocolInfo(Channel channel) {
#if MC_VER < MC_1_21_1
        var current = channel.attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get();
        if (hassium$identity != current) { hassium$identity = current; hassium$binding = EgressProtocol.bound(current, flow); }
#else
        if (hassium$identity != protocolInfo) { hassium$identity = protocolInfo; hassium$binding = EgressProtocol.bound(protocolInfo); }
#endif
        return hassium$binding;
    }
}
