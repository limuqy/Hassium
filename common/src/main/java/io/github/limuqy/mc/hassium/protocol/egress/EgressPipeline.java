package io.github.limuqy.mc.hassium.protocol.egress;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.compat.EgressDecoderBinding;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.protocol.*;
import io.github.limuqy.mc.hassium.compat.EgressProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.AttributeKey;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import java.util.IdentityHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/** All pipeline mutations and metadata associations are confined to this connection's EventLoop. */
public final class EgressPipeline {
    static final String OUT = "hassium_egress_v3", IN = "hassium_ingress_v3";
    private static final AttributeKey<IdentityHashMap<ByteBuf, Metadata>> META = AttributeKey.valueOf("hassium:encoded_v3");
    private static final AttributeKey<Boolean> CALLBACK = AttributeKey.valueOf("hassium:callback_v3");
    private static final AttributeKey<Boolean> WIRE_PLAY = AttributeKey.valueOf("hassium:wire_play_v3");
    public static final EgressMetrics METRICS = new EgressMetrics();
    private static final ConcurrentHashMap<Channel, EgressOutboundHandler> SERVERS = new ConcurrentHashMap<>();
    static void register(Channel channel, EgressOutboundHandler handler) { SERVERS.put(channel, handler); }
    static void unregister(Channel channel, EgressOutboundHandler handler) { SERVERS.remove(channel, handler); }
    public static void flushAll() { SERVERS.values().forEach(EgressOutboundHandler::requestFlush); }
    public static void flushConnection(Connection connection) {
        Channel channel = ConnectionChannelAccess.getConnectionChannel(connection);
        EgressOutboundHandler handler = channel == null ? null : SERVERS.get(channel); if (handler != null) handler.requestFlush();
    }
    static final ExecutorService WORKERS = Executors.newFixedThreadPool(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)), r -> {
        Thread t = new Thread(r, "Hassium-EgressV3"); t.setDaemon(true); return t;
    });
    record Metadata(String key, EgressProtocol protocol, boolean barrier) {}
    @FunctionalInterface interface CarrierEncoder { void encode(EgressProtocol protocol, ByteBuf output, byte[] frame); }
    public static void callback(Channel channel, boolean callback) { if (channel != null) channel.attr(CALLBACK).set(callback); }
    public static void installServer(ChannelHandlerContext encoder) {
        Channel channel = encoder.channel();
        if (channel.pipeline().get(OUT) != null) return;
        Connection connection = channel.pipeline().get(Connection.class);
        if (connection == null || connection.isMemoryConnection()) return;
        channel.attr(META).set(new IdentityHashMap<>());
        channel.pipeline().addBefore(encoder.name(), OUT, new EgressOutboundHandler(connection));
        // After framing and compression in outbound traversal: count only successfully written TCP bytes.
        if (channel.pipeline().get("prepender") != null) channel.pipeline().addBefore("prepender", "hassium_wire_meter", new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                int size = msg instanceof ByteBuf b ? b.readableBytes() : 0;
                ChannelPromise observed = promise.isVoid() ? ctx.newPromise() : promise;
                boolean play = Boolean.TRUE.equals(ctx.channel().attr(WIRE_PLAY).get());
                observed.addListener(f -> { if (f.isSuccess() && play) METRICS.wireBytes.add(size); });
                ctx.write(msg, observed);
            }
        });
    }
    public static void encoded(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf bytes, EgressProtocol protocol) {
        var map = ctx.channel().attr(META).get();
        if (map == null) return;
        String key = null;
        try { var type = PacketTypeHelper.getPacketType(packet); if (type != null) key = Services.NETWORK_MANAGER.egressPacketKey(packet, bytes, type.toString()); }
        catch (Exception ignored) { /* Unknown metadata is a direct barrier, never a speculative decode. */ }
        boolean barrier = EgressProtocol.terminal(packet) || Boolean.TRUE.equals(ctx.channel().attr(CALLBACK).get())
                || key == null || key.contains("bundle_delimiter") || key.contains("disconnect")
                || key.contains("keep_alive") || key.contains("start_configuration")
                || Services.NETWORK_MANAGER.isEgressControlChannel(key) || !PacketCompressionBlacklist.shouldAggregate(key);
        if (map.size() >= EgressFrameCodec.MAX_PACKETS) map.clear();
        map.put(bytes, new Metadata(key, protocol, barrier));
        if (protocol.id() == ConnectionProtocol.PLAY) AggregatedPacketExporter.exportNativeBytes(
                ctx.pipeline().get(Connection.class), key == null ? "unknown" : key, bytes);
    }
    static Metadata take(Channel channel, ByteBuf bytes) {
        var map = channel.attr(META).get(); return map == null ? null : map.remove(bytes);
    }
    static boolean outboundPlay(Channel channel) {
        Object encoder = channel.pipeline().get("encoder");
        if (!(encoder instanceof io.github.limuqy.mc.hassium.compat.EgressEncoderBinding binding)) return false;
        EgressProtocol protocol = binding.hassium$getNativeProtocol(channel);
        return protocol != null && protocol.id() == ConnectionProtocol.PLAY;
    }
    static void writeMeasured(ChannelHandlerContext ctx, Object bytes, ChannelPromise promise, boolean play) {
        Boolean previous = ctx.channel().attr(WIRE_PLAY).get(); ctx.channel().attr(WIRE_PLAY).set(play);
        try { ctx.write(bytes, promise); } finally { ctx.channel().attr(WIRE_PLAY).set(previous); }
    }
    public static void activateClient(Connection connection, Runnable ack) {
        Channel channel = ConnectionChannelAccess.getConnectionChannel(connection);
        if (channel == null || connection.isMemoryConnection()) throw EgressBytes.invalid("Missing TCP pipeline");
        channel.eventLoop().execute(() -> {
            try {
                if (!channel.isActive()) return;
                EgressProtocol protocol = inboundProtocol(channel);
                if (protocol == null || protocol.id() != ConnectionProtocol.PLAY) throw EgressBytes.invalid("Native PLAY decoder is not ready");
                if (channel.pipeline().get(IN) == null) channel.pipeline().addBefore("decoder", IN, new EgressInboundHandler(connection));
                HassiumConnectionRegistry.markEnabled(connection);
                ack.run();
            } catch (Throwable e) { fail(connection, channel.pipeline().firstContext(), e); }
        });
    }
    static EgressProtocol inboundProtocol(Channel channel) {
        Object decoder = channel.pipeline().get("decoder");
        return decoder instanceof EgressDecoderBinding access ? access.hassium$getProtocolInfo(channel) : null;
    }
    public static void inboundReady(Channel channel) {
        if (channel != null && channel.pipeline().get(IN) instanceof EgressInboundHandler handler) handler.protocolReady();
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void encodeCarrier(EgressProtocol protocol, ByteBuf output, byte[] frame) {
        protocol.encode(output, Services.NETWORK_MANAGER.createAggregationCarrier(frame));
    }
    static int compressionLevel() { return HassiumConfigService.getInstance().getCompressionLevel(); }
    public static void disconnectInvalid(Connection connection, ChannelHandlerContext ctx, Throwable cause) { fail(connection, ctx, cause); }
    static void fail(Connection connection, ChannelHandlerContext ctx, Throwable cause) {
        METRICS.errors.increment();
        Constants.LOG.error("Hassium egress v3 connection failed", cause);
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        connection.disconnect(Component.literal("Hassium egress v3: " + message));
        ctx.close();
    }
    private EgressPipeline() {}
}
