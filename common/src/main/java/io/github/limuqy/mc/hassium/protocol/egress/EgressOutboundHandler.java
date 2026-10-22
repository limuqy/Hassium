package io.github.limuqy.mc.hassium.protocol.egress;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.mixin.server.CompressionThresholdAccess;
import io.github.limuqy.mc.hassium.protocol.DictionaryManager;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.server.ServerNetworkGate;
import io.github.limuqy.mc.hassium.compat.EgressProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.*;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.function.*;

/** One EventLoop FIFO for native encoded writes, compression jobs, barriers and flushes. */
final class EgressOutboundHandler extends ChannelDuplexHandler {
    static final int HARD_LIMIT = 8 * 1024 * 1024, SOFT_LIMIT = 6 * 1024 * 1024;
    private final Connection connection;
    private final BooleanSupplier enabledGate;
    private final Function<ByteBuf, EgressPipeline.Metadata> metadata;
    private final EgressPipeline.CarrierEncoder carrierEncoder;
    private final Executor worker;
    private final BiConsumer<ChannelHandlerContext, Throwable> failure;
    private final Supplier<EgressFrameCodec.Dictionary> dictionarySupplier;
    private EgressFrameCodec codec;
    private io.github.limuqy.mc.hassium.protocol.DictionarySnapshot cachedSnapshot;
    private EgressFrameCodec.Dictionary cachedDictionary;
    private final Object codecLifecycle = new Object();
    private final ArrayDeque<Object> fifo = new ArrayDeque<>();
    private final Set<Batch> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Write> pending = new ArrayList<>();
    private ChannelHandlerContext context;
    private EgressProtocol protocol;
    private long epoch, queuedBytes;
    private int pendingBytes;
    private boolean active, paused, busy, flushRequested;
    private volatile boolean closed;
    private ScheduledFuture<?> timer;
    private static final class Write {
        ByteBuf bytes; final ChannelPromise promise; final EgressPipeline.Metadata meta; final long enqueued;
        final boolean play;
        boolean completed;
        Write(ByteBuf bytes, ChannelPromise promise, EgressPipeline.Metadata meta, long enqueued, boolean play) {
            this.bytes = bytes; this.promise = promise; this.meta = meta; this.enqueued = enqueued;
            this.play = play;
        }
    }
    private record Batch(List<Write> writes, EgressProtocol protocol, long epoch, int size, long sealedAt) {}
    private static final Object FLUSH = new Object();

    EgressOutboundHandler(Connection connection) {
        this(connection, () -> HassiumConnectionRegistry.isEnabled(connection)
                && HassiumConfigService.getInstance().isPacketAggregationEnabled() && ServerNetworkGate.isNetworkServerActive(),
                null,
                EgressPipeline::encodeCarrier, EgressPipeline.WORKERS, (ctx, cause) -> EgressPipeline.fail(connection, ctx, cause),
                null);
    }
    EgressOutboundHandler(Connection connection, BooleanSupplier enabledGate, Function<ByteBuf, EgressPipeline.Metadata> metadata,
                          EgressPipeline.CarrierEncoder carrierEncoder, Executor worker, BiConsumer<ChannelHandlerContext, Throwable> failure) {
        this(connection, enabledGate, metadata, carrierEncoder, worker, failure, () -> null);
    }
    private EgressOutboundHandler(Connection connection, BooleanSupplier enabledGate, Function<ByteBuf, EgressPipeline.Metadata> metadata,
                                  EgressPipeline.CarrierEncoder carrierEncoder, Executor worker, BiConsumer<ChannelHandlerContext, Throwable> failure,
                                  Supplier<EgressFrameCodec.Dictionary> dictionarySupplier) {
        this.connection = connection; this.enabledGate = enabledGate; this.metadata = metadata;
        this.carrierEncoder = carrierEncoder; this.worker = worker; this.failure = failure;
        this.dictionarySupplier = dictionarySupplier == null ? this::activeDictionary : dictionarySupplier;
    }
    private EgressFrameCodec.Dictionary activeDictionary() {
        var snapshot = DictionaryManager.getActiveSnapshot();
        if (snapshot != cachedSnapshot) {
            cachedSnapshot = snapshot;
            cachedDictionary = snapshot == null || snapshot.isEmpty() ? null : new EgressFrameCodec.Dictionary(snapshot.epoch(), snapshot.data());
        }
        return cachedDictionary;
    }
    @Override public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; EgressPipeline.register(ctx.channel(), this); }
    void requestFlush() { context.executor().execute(() -> { if (!closed) { seal(); queueFlush(); drain(); } }); }
    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (closed) { ReferenceCountUtil.release(msg); if (!promise.isVoid()) promise.tryFailure(new ClosedChannelException()); return; }
        if (!(msg instanceof ByteBuf bytes)) {
            // Unknown outbound messages must not leapfrog a compression job.
            if (queuedBytes + 128 > HARD_LIMIT) {
                ReferenceCountUtil.release(msg);
                var cause = EgressBytes.invalid("Outbound control queue exceeds 8 MiB");
                if (!promise.isVoid()) promise.tryFailure(cause);
                fail(cause); return;
            }
            queuedBytes += 128;
            seal(); fifo.add(new Other(msg, promise)); drain(); return;
        }
        var meta = metadata == null ? EgressPipeline.take(ctx.channel(), bytes) : metadata.apply(bytes);
        int size = bytes.readableBytes();
        boolean play = meta == null ? EgressPipeline.outboundPlay(ctx.channel()) : meta.protocol().id() == ConnectionProtocol.PLAY;
        if (play) {
            EgressPipeline.METRICS.originalBytes.add(size); EgressPipeline.METRICS.packets.increment();
        }
        boolean enabled = meta != null && meta.protocol().id() == ConnectionProtocol.PLAY
                && enabledGate.getAsBoolean();
        if (enabled != active || enabled && protocol != meta.protocol()) {
            seal(); active = enabled;
            if (enabled) { epoch++; protocol = meta.protocol(); }
        }
        int maxBatch = Math.max(1024, Math.min(EgressFrameCodec.MAX_FRAME_BYTES - 64,
                HassiumConfigService.getInstance().getAggregationMaxSize()));
        Write write = new Write(bytes, promise, meta, System.nanoTime(), play);
        // Native login/recipe sync may exceed our aggregation backlog limit. When no
        // earlier work is retained here, hand it straight to Netty's ordered transport;
        // it is not a queued aggregation item and must keep the native packet limits.
        if ((!enabled || paused || meta.barrier() || size + 6 > maxBatch)
                && pending.isEmpty() && fifo.isEmpty() && !busy) {
            writeDirect(write, false);
            return;
        }
        if (queuedBytes + size + 128 > HARD_LIMIT) {
            bytes.release(); if (!promise.isVoid()) promise.tryFailure(EgressBytes.invalid("Outbound queue exceeds 8 MiB"));
            fail(EgressBytes.invalid("Outbound queue exceeds 8 MiB")); return;
        }
        queuedBytes += size + 128;
        if (queuedBytes >= SOFT_LIMIT) { paused = true; seal(); }
        if (!enabled || paused || meta.barrier() || size + 6 > maxBatch) {
            seal(); fifo.add(write); drain(); return;
        }
        if (pendingBytes + size + 6 > maxBatch || pending.size() >= EgressFrameCodec.MAX_PACKETS) seal();
        pending.add(write); pendingBytes += size + 6;
        if (timer == null) timer = ctx.executor().schedule(() -> { timer = null; seal(); queueFlush(); drain(); },
                Math.max(1, HassiumConfigService.getInstance().getAggregationMaxWaitTimeMs()), TimeUnit.MILLISECONDS);
        drain();
    }
    private record Other(Object message, ChannelPromise promise) {}
    @Override public void flush(ChannelHandlerContext ctx) {
        // Vanilla requests a flush for each send. Coalesce these while the bounded batch waits;
        // a barrier or watchdog seals it and places the flush after its writes.
        if (!pending.isEmpty()) flushRequested = true;
        else queueFlush();
        drain();
    }
    private void queueFlush() { if (fifo.peekLast() != FLUSH) fifo.add(FLUSH); }
    private void seal() {
        if (timer != null) { timer.cancel(false); timer = null; }
        if (pending.isEmpty()) return;
        long sealedAt = EgressProfiler.ENABLED ? System.nanoTime() : 0;
        if (EgressProfiler.ENABLED) for (Write write : pending) EgressProfiler.Stage.BATCH_WAIT.record(sealedAt - write.enqueued, -1);
        fifo.add(new Batch(List.copyOf(pending), protocol, epoch, pending.stream().mapToInt(w -> w.bytes.readableBytes() + 128).sum(), sealedAt));
        pending.clear(); pendingBytes = 0;
        if (flushRequested) { flushRequested = false; queueFlush(); }
    }
    private void drain() {
        if (closed || busy) return;
        while (!fifo.isEmpty() && !closed) {
            Object item = fifo.remove();
            if (item == FLUSH) context.flush();
            else if (item instanceof Write write) writeDirect(write, true);
            else if (item instanceof Other other) {
                ChannelPromise result = context.newPromise();
                result.addListener(f -> {
                    if (!closed) queuedBytes -= 128;
                    if (!other.promise.isVoid()) { if (f.isSuccess()) other.promise.trySuccess(); else other.promise.tryFailure(f.cause()); }
                    if (!f.isSuccess()) fail(f.cause());
                });
                EgressPipeline.writeMeasured(context, other.message, result, false);
            }
            else if (item instanceof Batch batch) { compress(batch); return; }
        }
        if (queuedBytes == 0) paused = false;
    }
    private void writeDirect(Write write, boolean accounted) {
        int size = write.bytes.readableBytes();
        ChannelPromise result = context.newPromise();
        result.addListener(f -> {
            if (accounted && !closed) queuedBytes -= size + 128;
            if (f.isSuccess()) { if (write.play) EgressPipeline.METRICS.bypassBytes.add(size); complete(write, null); }
            else { complete(write, f.cause()); fail(f.cause()); }
            if (queuedBytes == 0) paused = false;
        });
        ByteBuf bytes = write.bytes; write.bytes = null;
        EgressPipeline.writeMeasured(context, bytes, result, write.play);
    }
    private void compress(Batch batch) {
        busy = true; inFlight.add(batch);
        long preparationStart = EgressProfiler.ENABLED ? System.nanoTime() : 0;
        EgressProfiler.Stage.FIFO_WAIT.record(preparationStart - batch.sealedAt, -1);
        long preparationAllocated = EgressProfiler.allocated();
        List<EgressFrameCodec.PacketBytes> packets = new ArrayList<>(batch.writes.size());
        try {
            for (Write write : batch.writes) {
                ByteBuf bytes = write.bytes; write.bytes = null;
                try {
                    byte[] data = new byte[bytes.readableBytes()]; bytes.readBytes(data);
                    packets.add(EgressFrameCodec.PacketBytes.owned(write.meta.key(), data));
                } finally { bytes.release(); }
            }
            var dictionary = dictionarySupplier.get();
            EgressProfiler.finish(EgressProfiler.Stage.PREPARE, preparationStart, preparationAllocated);
            long submitted = EgressProfiler.ENABLED ? System.nanoTime() : 0;
            worker.execute(() -> {
                long start = System.nanoTime();
                long cpu = EgressCpuClock.now();
                long allocated = EgressProfiler.allocated();
                EgressProfiler.Stage.WORKER_QUEUE.record(start - submitted, -1);
                SampleCollector samples = new SampleCollector(connection);
                try {
                    EgressFrameCodec.Encoded frame;
                    synchronized (codecLifecycle) {
                        if (closed) return;
                        if (codec == null) codec = new EgressFrameCodec(EgressPipeline.compressionLevel());
                        codec.setLevel(EgressPipeline.compressionLevel());
                        frame = codec.encode(packets, batch.epoch, dictionary, samples);
                    }
                    long codecNs = System.nanoTime() - start;
                    EgressPipeline.METRICS.compressionNanos.add(codecNs);
                    EgressPipeline.METRICS.compressionCpuNanos.add(EgressCpuClock.now() - cpu);
                    EgressProfiler.Stage.CODEC.record(codecNs, EgressProfiler.allocationDelta(allocated));
                    samples.collect();
                    long encodedAt = EgressProfiler.ENABLED ? System.nanoTime() : 0;
                    context.executor().execute(() -> {
                        EgressProfiler.elapsed(EgressProfiler.Stage.EVENT_LOOP_RETURN, encodedAt);
                        send(batch, frame);
                    });
                } catch (Throwable e) { context.executor().execute(() -> fail(e)); }
            });
        } catch (Throwable e) { fail(e); }
    }
    private static final class SampleCollector implements Consumer<byte[]> {
        private final Connection connection;
        private byte[] logical;
        SampleCollector(Connection connection) { this.connection = connection; }
        public void accept(byte[] logical) { this.logical = logical; }
        void collect() {
            long start = EgressProfiler.ENABLED ? System.nanoTime() : 0, beforeAllocated = EgressProfiler.allocated();
            // Reuse the immutable native logical batch. The dictionary hooks decide whether to retain it.
            DictionaryManager.collectSample(logical); DictionaryManager.collectCorpusSample(connection, logical);
            EgressProfiler.finish(EgressProfiler.Stage.SAMPLE, start, beforeAllocated);
        }
    }
    private void send(Batch batch, EgressFrameCodec.Encoded frame) {
        if (closed) return;
        long writeStarted = EgressProfiler.ENABLED ? System.nanoTime() : 0;
        ByteBuf carrier = context.alloc().buffer();
        try {
            carrierEncoder.encode(batch.protocol, carrier, frame.bytes());
            int lengthPrefix = 1; for (int n = frame.bytes().length; (n >>>= 7) != 0;) lengthPrefix++;
            int envelopeFixedBytes = carrier.readableBytes() - frame.bytes().length - lengthPrefix;
            int compressionPrefixBytes = context.pipeline().get("compress") == null ? 0 : 1;
            ChannelPromise result = context.newPromise();
            result.addListener(f -> {
                EgressProfiler.elapsed(EgressProfiler.Stage.WRITE, writeStarted);
                inFlight.remove(batch); if (!closed) queuedBytes -= batch.size;
                for (Write write : batch.writes) complete(write, f.isSuccess() ? null : f.cause());
                if (!f.isSuccess()) { fail(f.cause()); return; }
                var metrics = EgressPipeline.METRICS;
                metrics.aggregateBytes.add(frame.bytes().length); metrics.frames.increment();
                metrics.exactReferences.add(frame.exactReferences()); metrics.templateReferences.add(frame.templateReferences());
                metrics.definitionBytes.add(frame.definitionBytes());
                io.github.limuqy.mc.hassium.protocol.AggregatedPacketExporter.exportV3Frame(connection, frame.bytes(), batch.writes.size(), frame.availableDictionaryEpoch(), envelopeFixedBytes, compressionPrefixBytes);
                if (queuedBytes == 0) paused = false;
            });
            ChannelHandler compress = context.pipeline().get("compress");
            if (compress instanceof CompressionEncoder encoder && compress instanceof CompressionThresholdAccess access) {
                int threshold = access.hassium$getThreshold(); encoder.setThreshold(Integer.MAX_VALUE);
                try { EgressPipeline.writeMeasured(context, carrier, result, true); } finally { encoder.setThreshold(threshold); }
            } else EgressPipeline.writeMeasured(context, carrier, result, true);
            carrier = null; busy = false; drain();
        } catch (Throwable e) { fail(e); }
        finally { if (carrier != null && carrier.refCnt() > 0) carrier.release(); }
    }
    private void complete(Write write, Throwable failure) {
        if (write.completed) return;
        write.completed = true;
        if (write.play) {
            long elapsed = System.nanoTime() - write.enqueued;
            EgressPipeline.METRICS.queueNanos.add(elapsed);
            if (failure == null) EgressProfiler.Stage.OUTBOUND_TOTAL.record(elapsed, -1);
        }
        if (!write.promise.isVoid()) { if (failure == null) write.promise.trySuccess(); else write.promise.tryFailure(failure); }
    }
    private void fail(Throwable cause) {
        if (closed) return;
        cleanup(cause);
        // A peer's normal disconnect can fail already queued writes before channelInactive runs.
        // Promises still fail and resources close, but this is not a live v3 state/codec error.
        if (cause instanceof ClosedChannelException && !context.channel().isOpen()) { context.close(); return; }
        failure.accept(context, cause);
    }
    private void cleanup(Throwable cause) {
        if (closed) return; closed = true;
        EgressPipeline.unregister(context.channel(), this);
        if (timer != null) timer.cancel(false);
        for (Write write : pending) { ReferenceCountUtil.safeRelease(write.bytes); complete(write, cause); }
        pending.clear();
        for (Object item : fifo) {
            if (item instanceof Write write) { ReferenceCountUtil.safeRelease(write.bytes); complete(write, cause); }
            else if (item instanceof Batch batch) for (Write write : batch.writes) { ReferenceCountUtil.safeRelease(write.bytes); complete(write, cause); }
            else if (item instanceof Other other) { ReferenceCountUtil.release(other.message); if (!other.promise.isVoid()) other.promise.tryFailure(cause); }
        }
        fifo.clear();
        for (Batch batch : inFlight) for (Write write : batch.writes) { ReferenceCountUtil.safeRelease(write.bytes); write.bytes = null; complete(write, cause); }
        inFlight.clear(); queuedBytes = 0;
        cachedSnapshot = null; cachedDictionary = null;
        worker.execute(() -> { synchronized (codecLifecycle) { if (codec != null) codec.close(); } });
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) { cleanup(new ClosedChannelException()); ctx.fireChannelInactive(); }
    @Override public void handlerRemoved(ChannelHandlerContext ctx) { cleanup(new ClosedChannelException()); }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { fail(cause); }
}
