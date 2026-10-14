package io.github.limuqy.mc.hassium.compat;

import io.netty.channel.Channel;

/** The real native binding, with legacy channel protocol attributes hidden behind compat. */
public interface EgressEncoderBinding {
    EgressProtocol hassium$getNativeProtocol(Channel channel);
}
