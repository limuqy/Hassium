package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.compat.mods.NeoForgeEncodedPacketReuse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
#if MC_VER >= MC_1_21_1
import net.minecraft.network.codec.StreamCodec;
#endif
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Pseudo
@Mixin(targets = "net.neoforged.neoforge.network.filters.GenericPacketSplitter", remap = false)
public abstract class MixinNeoForgePacketSplitter {
#if MC_VER >= MC_1_21_1
    @Redirect(method = "encode", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void hassium$rememberEncoded(StreamCodec codec, Object buffer, Object packet, ChannelHandlerContext ctx, Packet<?> original, List<Object> out) {
        codec.encode(buffer, packet);
        NeoForgeEncodedPacketReuse.capture(ctx, original, (ByteBuf)buffer);
    }
    @Inject(method = "encode", at = @At("RETURN"))
    private void hassium$discardSplit(ChannelHandlerContext ctx, Packet<?> packet, List<Object> out, CallbackInfo ci) {
        NeoForgeEncodedPacketReuse.discardSplit(ctx, packet, out);
    }
#endif
}
