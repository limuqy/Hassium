package io.github.limuqy.mc.hassium.protocol.egress;

import io.github.limuqy.mc.hassium.protocol.DictionaryManager;
import io.github.limuqy.mc.hassium.protocol.HassiumPacketIds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Ordered raw receive FIFO, immediately before the currently bound native decoder and bundler. */
final class EgressInboundHandler extends ChannelInboundHandlerAdapter {
    private final Connection connection;
    private final Function<ByteBuf, byte[]> extractor;
    private final Executor worker;
    private final BiConsumer<ChannelHandlerContext, Throwable> failure;
    private final BooleanSupplier ready;
    private final EgressFrameCodec codec = new EgressFrameCodec(EgressPipeline.compressionLevel());
    private final ArrayDeque<Object> fifo = new ArrayDeque<>();
    private ChannelHandlerContext context;
    private ProtocolInfo<?> identifiedProtocol;
    private int payloadPacketId = -1;
    private long queuedBytes;
    private boolean busy, closed;
    private static final Object READ_COMPLETE = new Object();
    EgressInboundHandler(Connection connection) { this(connection, null, EgressPipeline.WORKERS, (ctx, cause) -> EgressPipeline.fail(connection, ctx, cause), null); }
    EgressInboundHandler(Connection connection, Function<ByteBuf, byte[]> extractor, Executor worker, BiConsumer<ChannelHandlerContext, Throwable> failure) {
        this(connection, extractor, worker, failure, () -> true);
    }
    EgressInboundHandler(Connection connection, Function<ByteBuf, byte[]> extractor, Executor worker,
                         BiConsumer<ChannelHandlerContext, Throwable> failure, BooleanSupplier ready) {
        this.connection = connection; this.extractor = extractor; this.worker = worker; this.failure = failure; this.ready = ready;
    }
    void protocolReady() { context.executor().execute(this::drain); }
    @Override public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (closed) { ReferenceCountUtil.release(msg); return; }
        if (msg instanceof ByteBuf bytes) {
            queuedBytes += bytes.readableBytes() + 128;
            if (queuedBytes > EgressOutboundHandler.HARD_LIMIT) { bytes.release(); fail(EgressBytes.invalid("Inbound FIFO exceeds 8 MiB")); return; }
        }
        fifo.add(msg); drain();
    }
    @Override public void channelReadComplete(ChannelHandlerContext ctx) {
        if (fifo.peekLast() != READ_COMPLETE) fifo.add(READ_COMPLETE);
        drain();
    }
    private void drain() {
        if (busy || closed) return;
        try {
            while (!fifo.isEmpty() && !closed) {
                // Terminal packets unbind the native decoder. Bytes already read while a worker
                // ran must wait too, until vanilla installs the next protocol on EventLoop.
                if (ready == null ? EgressPipeline.inboundProtocol(context.channel()) == null : !ready.getAsBoolean()) return;
                Object msg = fifo.remove();
                if (msg == READ_COMPLETE) { context.fireChannelReadComplete(); continue; }
                if (!(msg instanceof ByteBuf bytes)) { context.fireChannelRead(msg); continue; }
                int size = bytes.readableBytes() + 128;
                byte[] frame;
                try { frame = extractor == null ? carrier(bytes) : extractor.apply(bytes); }
                catch (Throwable e) { bytes.release(); throw e; }
                if (frame == null) { queuedBytes -= size; context.fireChannelRead(bytes); }
                else {
                    bytes.release(); busy = true;
                    worker.execute(() -> {
                        try {
                            List<byte[]> packets = codec.decode(frame, DictionaryManager::getAggregationDictForEpoch);
                            context.executor().execute(() -> {
                                if (closed) return;
                                queuedBytes -= size;
                                // Each packet re-enters the actual loader-bound codec; no RawCustomPayload is synthesized.
                                for (byte[] packet : packets) {
                                    if (closed || !context.channel().isActive()) break;
                                    context.fireChannelRead(Unpooled.wrappedBuffer(packet));
                                }
                                busy = false; drain();
                            });
                        } catch (Throwable e) { context.executor().execute(() -> fail(e)); }
                    });
                    return;
                }
            }
        } catch (Throwable e) { fail(e); }
    }
    private byte[] carrier(ByteBuf bytes) {
        ProtocolInfo<?> protocol = EgressPipeline.inboundProtocol(context.channel());
        if (protocol == null || protocol.id() != ConnectionProtocol.PLAY) return null;
        if (identifiedProtocol != protocol) {
            ByteBuf probe = context.alloc().buffer();
            try { EgressPipeline.encodeCarrier(protocol, probe, new byte[0]); payloadPacketId = new FriendlyByteBuf(probe).readVarInt(); identifiedProtocol = protocol; }
            finally { probe.release(); }
        }
        FriendlyByteBuf reader = new FriendlyByteBuf(bytes.duplicate());
        if (reader.readVarInt() != payloadPacketId) return null;
        String channel = reader.readUtf(32767);
        if (!HassiumPacketIds.AGGREGATION_S2C.equals(channel)) return null;
        int length = reader.readVarInt();
        if (length <= 0 || length > EgressFrameCodec.MAX_FRAME_BYTES || reader.readableBytes() != length) throw EgressBytes.invalid("Invalid native carrier envelope");
        byte[] frame = new byte[length]; reader.readBytes(frame); return frame;
    }
    private void fail(Throwable cause) {
        if (closed) return;
        cleanup(); failure.accept(context, cause);
    }
    private void cleanup() {
        if (closed) return; closed = true;
        for (Object msg : fifo) if (msg != READ_COMPLETE) ReferenceCountUtil.release(msg);
        fifo.clear(); queuedBytes = 0;
        worker.execute(codec::close);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { fail(cause); }
    @Override public void channelInactive(ChannelHandlerContext ctx) { cleanup(); ctx.fireChannelInactive(); }
    @Override public void handlerRemoved(ChannelHandlerContext ctx) { cleanup(); }
}
