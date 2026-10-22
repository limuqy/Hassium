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
import io.github.limuqy.mc.hassium.compat.EgressProtocol;
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
    private EgressProtocol identifiedProtocol;
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
            // An immediately deliverable native packet is governed by the native
            // decoder's limits, not the budget for bytes waiting behind our worker.
            if (queuedBytes > EgressOutboundHandler.HARD_LIMIT && (busy || !fifo.isEmpty() || !nativeReady())) {
                bytes.release(); fail(EgressBytes.invalid("Inbound FIFO exceeds 8 MiB")); return;
            }
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
                if (!nativeReady()) return;
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
                    long submitted = EgressProfiler.ENABLED ? System.nanoTime() : 0;
                    worker.execute(() -> {
                        try {
                            long started = EgressProfiler.ENABLED ? System.nanoTime() : 0, allocated = EgressProfiler.allocated();
                            EgressProfiler.Stage.INBOUND_WORKER_QUEUE.record(started - submitted, -1);
                            List<byte[]> packets = codec.decode(frame, DictionaryManager::getAggregationDictForEpoch);
                            EgressProfiler.finish(EgressProfiler.Stage.DECODE, started, allocated);
                            long decodedAt = EgressProfiler.ENABLED ? System.nanoTime() : 0;
                            context.executor().execute(() -> {
                                if (closed) return;
                                long deliveryStart = EgressProfiler.ENABLED ? System.nanoTime() : 0, deliveryAllocated = EgressProfiler.allocated();
                                EgressProfiler.Stage.INBOUND_EVENT_LOOP_RETURN.record(deliveryStart - decodedAt, -1);
                                queuedBytes -= size;
                                // Each packet re-enters the actual loader-bound codec; no RawCustomPayload is synthesized.
                                for (byte[] packet : packets) {
                                    if (closed || !context.channel().isActive()) break;
                                    context.fireChannelRead(Unpooled.wrappedBuffer(packet));
                                }
                                EgressProfiler.finish(EgressProfiler.Stage.NATIVE_DELIVERY, deliveryStart, deliveryAllocated);
                                busy = false; drain();
                            });
                        } catch (Throwable e) { context.executor().execute(() -> fail(e)); }
                    });
                    return;
                }
            }
        } catch (Throwable e) { fail(e); }
    }
    private boolean nativeReady() {
        return ready == null ? EgressPipeline.inboundProtocol(context.channel()) != null : ready.getAsBoolean();
    }
    private byte[] carrier(ByteBuf bytes) {
        EgressProtocol protocol = EgressPipeline.inboundProtocol(context.channel());
        if (protocol == null || protocol.id() != ConnectionProtocol.PLAY) return null;
        if (identifiedProtocol != protocol) {
            ByteBuf probe = context.alloc().buffer();
            try { EgressPipeline.encodeCarrier(protocol, probe, new byte[0]); payloadPacketId = new FriendlyByteBuf(probe.duplicate()).readVarInt(); io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.inspectAggregationCarrier(probe); identifiedProtocol = protocol; }
            finally { probe.release(); }
        }
        FriendlyByteBuf reader = new FriendlyByteBuf(bytes.duplicate());
        if (reader.readVarInt() != payloadPacketId) return null;
        String channel = reader.readUtf(32767);
        return io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.extractAggregationFrame(channel, reader);
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
