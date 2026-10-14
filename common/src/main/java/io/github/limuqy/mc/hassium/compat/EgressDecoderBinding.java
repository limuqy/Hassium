package io.github.limuqy.mc.hassium.compat;

import io.netty.channel.Channel;

public interface EgressDecoderBinding {
    EgressProtocol hassium$getProtocolInfo(Channel channel);
}
