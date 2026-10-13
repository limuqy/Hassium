package io.github.limuqy.mc.hassium.compat.mods;

import io.github.limuqy.mc.hassium.compat.EgressEncoderBinding;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import java.util.List;

/** Reuse the splitter's native size-check bytes; do not rerun arbitrary Mod codecs for small packets. */
public final class NeoForgeEncodedPacketReuse {
    private static final AttributeKey<Cached> CACHE = AttributeKey.valueOf("hassium:neoforge_preencoded_v3");
    private record Cached(Packet<?> packet, ProtocolInfo<?> protocol, byte[] bytes) {}
    public static void capture(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf buffer) {
        Connection connection = ctx.pipeline().get(Connection.class);
        Object encoder = ctx.pipeline().get("encoder");
        if (connection == null || !HassiumConnectionRegistry.isEnabled(connection) || !(encoder instanceof EgressEncoderBinding binding)) return;
        var protocol = binding.hassium$getNativeProtocol();
        if (protocol.flow() != PacketFlow.CLIENTBOUND || protocol.id() != ConnectionProtocol.PLAY) return;
        if (buffer.readableBytes() > 8 * 1024 * 1024) { ctx.channel().attr(CACHE).set(null); return; }
        byte[] bytes = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), bytes);
        ctx.channel().attr(CACHE).set(new Cached(packet, protocol, bytes));
    }
    public static void discardSplit(ChannelHandlerContext ctx, Packet<?> packet, List<Object> output) {
        boolean original = output.stream().anyMatch(value -> value == packet);
        if (!original) ctx.channel().attr(CACHE).set(null);
    }
    public static boolean writeCached(ChannelHandlerContext ctx, Packet<?> packet, ProtocolInfo<?> protocol, ByteBuf output) {
        Cached cached = ctx.channel().attr(CACHE).getAndSet(null);
        if (cached == null || cached.packet != packet || cached.protocol != protocol) return false;
        output.writeBytes(cached.bytes); return true;
    }
    private NeoForgeEncodedPacketReuse() {}
}
