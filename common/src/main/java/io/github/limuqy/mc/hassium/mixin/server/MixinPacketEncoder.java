package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.compat.EgressProtocol;
import io.github.limuqy.mc.hassium.protocol.egress.EgressPipeline;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class MixinPacketEncoder implements io.github.limuqy.mc.hassium.compat.EgressEncoderBinding {
#if MC_VER < MC_1_21_1
    @Shadow @Final private PacketFlow flow;
#else
    @Shadow @Final private net.minecraft.network.ProtocolInfo<?> protocolInfo;
#endif
    @Unique private Object hassium$identity;
    @Unique private EgressProtocol hassium$binding;
    @Unique private final java.util.IdentityHashMap<ByteBuf, EgressProtocol> hassium$captures = new java.util.IdentityHashMap<>();
    @Unique public EgressProtocol hassium$getNativeProtocol(Channel channel) {
#if MC_VER < MC_1_21_1
        var current = channel.attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get();
        if (hassium$identity != current) { hassium$identity = current; hassium$binding = EgressProtocol.bound(current, flow); }
#else
        if (hassium$identity != protocolInfo) { hassium$identity = protocolInfo; hassium$binding = EgressProtocol.bound(protocolInfo); }
#endif
        return hassium$binding;
    }
#if MC_VER >= MC_1_21_1
    @Redirect(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void hassium$encodeOnce(net.minecraft.network.codec.StreamCodec codec, Object buffer, Object packet,
                                   ChannelHandlerContext ctx, Packet<?> original, ByteBuf output) {
        if (!io.github.limuqy.mc.hassium.compat.mods.NeoForgeEncodedPacketReuse.writeCached(ctx, original, hassium$captures.get(output), output)) codec.encode(buffer, packet);
    }
#endif
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"))
    private void hassium$install(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf bytes, CallbackInfo ci) {
        EgressProtocol protocol = hassium$getNativeProtocol(ctx.channel());
        if (hassium$captures.size() >= 4096) hassium$captures.clear();
        hassium$captures.put(bytes, protocol);
        if (protocol != null && protocol.flow() == PacketFlow.CLIENTBOUND && protocol.id() == net.minecraft.network.ConnectionProtocol.PLAY) EgressPipeline.installServer(ctx);
    }
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"))
    private void hassium$capture(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf bytes, CallbackInfo ci) {
#if MC_VER < MC_1_21_1
        if(io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled() && packet instanceof net.minecraft.network.protocol.login.ClientboundCustomQueryPacket q)
            io.github.limuqy.mc.hassium.Constants.LOG.info("[SLEEP_LOGIN_PROBE] encoded channel={} id={} bytes={}",ctx.channel().id(),q.getTransactionId(),bytes.readableBytes());
#endif
        EgressProtocol protocol = hassium$captures.remove(bytes);
        if (protocol != null && protocol.flow() == PacketFlow.CLIENTBOUND) EgressPipeline.encoded(ctx, packet, bytes, protocol);
    }
}
