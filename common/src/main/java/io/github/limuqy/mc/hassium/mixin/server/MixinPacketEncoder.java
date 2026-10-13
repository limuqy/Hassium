package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.protocol.egress.EgressPipeline;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class MixinPacketEncoder implements io.github.limuqy.mc.hassium.compat.EgressEncoderBinding {
    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    @Unique public ProtocolInfo<?> hassium$getNativeProtocol() { return protocolInfo; }

    @Redirect(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void hassium$encodeOnce(net.minecraft.network.codec.StreamCodec codec, Object buffer, Object packet,
                                   ChannelHandlerContext ctx, Packet<?> original, ByteBuf output) {
        if (!io.github.limuqy.mc.hassium.compat.mods.NeoForgeEncodedPacketReuse.writeCached(ctx, original, protocolInfo, output)) {
            codec.encode(buffer, packet);
        }
    }

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"))
    private void hassium$install(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf bytes, CallbackInfo ci) {
        if (protocolInfo.flow() == PacketFlow.CLIENTBOUND && protocolInfo.id() == net.minecraft.network.ConnectionProtocol.PLAY) EgressPipeline.installServer(ctx);
    }

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"))
    private void hassium$capture(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf bytes, CallbackInfo ci) {
        if (protocolInfo.flow() == PacketFlow.CLIENTBOUND) EgressPipeline.encoded(ctx, packet, bytes, protocolInfo);
    }
}
