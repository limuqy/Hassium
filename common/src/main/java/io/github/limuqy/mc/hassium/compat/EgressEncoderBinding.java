package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.ProtocolInfo;

/** Access to the protocol actually bound to this encoder, including loader decorations. */
public interface EgressEncoderBinding {
    ProtocolInfo<?> hassium$getNativeProtocol();
}
