package io.github.limuqy.mc.hassium.protocol.egress;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.*;
import io.github.limuqy.mc.hassium.compat.EgressProtocol;
import net.minecraft.network.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class EgressTransportTest {
    @Test void peerCloseFailsInFlightPromisesWithoutReportingLiveCodecFailure() {
        Worker worker = new Worker(); AtomicBoolean enabled = new AtomicBoolean(true);
        var reported = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new EgressOutboundHandler(new Connection(PacketFlow.SERVERBOUND), enabled::get,
                bytes -> new EgressPipeline.Metadata("fixture:test", PLAY, false),
                (protocol, bytes, frame) -> bytes.writeByte(99).writeBytes(frame), worker,
                (ctx, cause) -> { reported.incrementAndGet(); ctx.close(); });
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ChannelPromise promise = channel.newPromise(); channel.pipeline().write(raw(1), promise); handler.requestFlush(); channel.runPendingTasks();
        channel.close(); worker.cleanup(); channel.runPendingTasks();
        assertTrue(promise.isDone()); assertFalse(promise.isSuccess()); assertEquals(0, reported.get());
        channel.finishAndReleaseAll();
    }
    @Test void alreadyQueuedInputWaitsWhileTerminalPacketUnbindsNativeDecoder() {
        Worker worker = new Worker(); AtomicBoolean ready = new AtomicBoolean(true);
        var handler = new EgressInboundHandler(new Connection(PacketFlow.CLIENTBOUND), bytes -> null, worker,
                (ctx, cause) -> ctx.close(), ready::get);
        EmbeddedChannel channel = new EmbeddedChannel(handler, new ChannelInboundHandlerAdapter() {
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (((ByteBuf)msg).getUnsignedByte(0) == 1) ready.set(false);
                ctx.fireChannelRead(msg);
            }
        });
        try {
            channel.writeInbound(raw(1), raw(2)); ByteBuf terminal = channel.readInbound(); terminal.release();
            assertNull(channel.readInbound());
            ready.set(true); handler.protocolReady(); channel.runPendingTasks();
            ByteBuf nextProtocol = channel.readInbound(); assertEquals(2, nextProtocol.readUnsignedByte()); nextProtocol.release();
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
    @Test void disconnectBeforeWorkerStartsFailsOriginalAndDoesNotResurrectCodecOrWrite() {
        Worker worker = new Worker(); AtomicBoolean enabled = new AtomicBoolean(true);
        EmbeddedChannel channel = new EmbeddedChannel(output(worker, enabled));
        ChannelPromise original = channel.newPromise();
        channel.pipeline().writeAndFlush(raw(1)); channel.pipeline().write(raw(7), original);
        channel.close(); worker.cleanup(); channel.runPendingTasks();
        assertTrue(original.isDone()); assertFalse(original.isSuccess()); assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }
    @Test void pendingConnectionPassesNativeBytesUntilAcknowledged() {
        Worker worker = new Worker(); AtomicBoolean enabled = new AtomicBoolean(false);
        EmbeddedChannel channel = new EmbeddedChannel(output(worker, enabled));
        try {
            channel.pipeline().writeAndFlush(raw(1)); ByteBuf first = channel.readOutbound();
            assertEquals(1, first.readUnsignedByte()); first.release(); assertTrue(worker.jobs.isEmpty());
            enabled.set(true); channel.pipeline().writeAndFlush(raw(2)); channel.pipeline().writeAndFlush(raw(7));
            worker.next(channel); ByteBuf carrier = channel.readOutbound(), barrier = channel.readOutbound();
            assertNotNull(extract(carrier)); carrier.release(); barrier.release();
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
    private static final class Worker implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        public void execute(Runnable job) { jobs.add(job); }
        void next(EmbeddedChannel channel) { assertFalse(jobs.isEmpty()); jobs.remove().run(); channel.runPendingTasks(); }
        void cleanup() { while (!jobs.isEmpty()) jobs.remove().run(); }
    }
    private static final EgressProtocol PLAY = new EgressProtocol() {
        public ConnectionProtocol id() { return ConnectionProtocol.PLAY; }
        public PacketFlow flow() { return PacketFlow.CLIENTBOUND; }
        public void encode(ByteBuf output, Packet<?> packet) { throw new UnsupportedOperationException(); }
        public BundlerInfo bundlerInfo() { return null; }
    };
    private static ByteBuf raw(int value) { return Unpooled.buffer().writeByte(value); }
    private static ByteBuf wrap(byte[] frame) { return Unpooled.buffer().writeByte(99).writeBytes(frame); }
    private static byte[] extract(ByteBuf bytes) {
        if (bytes.getUnsignedByte(bytes.readerIndex()) != 99) return null;
        byte[] frame = new byte[bytes.readableBytes() - 1]; bytes.getBytes(bytes.readerIndex() + 1, frame); return frame;
    }
    private static EgressOutboundHandler output(Worker worker, AtomicBoolean enabled) {
        return new EgressOutboundHandler(new Connection(PacketFlow.SERVERBOUND), enabled::get,
                bytes -> new EgressPipeline.Metadata("fixture:test", PLAY, bytes.getUnsignedByte(bytes.readerIndex()) == 7),
                (protocol, bytes, frame) -> bytes.writeByte(99).writeBytes(frame), worker, (ctx, cause) -> ctx.close());
    }
    @Test void aggregatePromiseCompletesOnActualWriteAndBarrierCannotOvertakeWorker() {
        Worker worker = new Worker(); AtomicBoolean enabled = new AtomicBoolean(true);
        EmbeddedChannel channel = new EmbeddedChannel(output(worker, enabled));
        try {
            ChannelPromise original = channel.newPromise();
            channel.pipeline().write(raw(1), original); channel.flush();
            assertFalse(original.isDone());
            ChannelPromise barrier = channel.newPromise(); channel.pipeline().writeAndFlush(raw(7), barrier);
            assertFalse(original.isDone()); assertFalse(barrier.isDone()); assertNull(channel.readOutbound());
            worker.next(channel);
            assertTrue(original.isSuccess()); assertTrue(barrier.isSuccess());
            ByteBuf carrier = channel.readOutbound(), direct = channel.readOutbound();
            try (var decoder = new EgressFrameCodec(3)) { assertArrayEquals(new byte[]{1}, decoder.decode(extract(carrier), null).get(0)); }
            finally { carrier.release(); direct.release(); }
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
    @Test void failedCarrierFailsOriginalPromiseAndClosesWithoutSendingBarrier() {
        Worker worker = new Worker(); AtomicBoolean enabled = new AtomicBoolean(true);
        var failure = new IllegalStateException("write failure");
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) { ((ByteBuf)msg).release(); promise.tryFailure(failure); }
        }, output(worker, enabled));
        try {
            ChannelPromise first = channel.newPromise(), barrier = channel.newPromise();
            channel.pipeline().write(raw(1), first); channel.pipeline().writeAndFlush(raw(7), barrier);
            worker.next(channel);
            assertFalse(first.isSuccess()); assertSame(failure, first.cause());
            assertTrue(barrier.isDone()); assertFalse(barrier.isSuccess()); assertFalse(channel.isActive());
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
    @Test void disableDrainsThenBypassesAndReenableUsesIndependentNewEpoch() {
        Worker worker = new Worker(); AtomicBoolean enabled = new AtomicBoolean(true);
        EmbeddedChannel channel = new EmbeddedChannel(output(worker, enabled));
        try (var decoder = new EgressFrameCodec(3)) {
            channel.pipeline().writeAndFlush(raw(1)); enabled.set(false);
            channel.pipeline().writeAndFlush(raw(2)); enabled.set(true);
            channel.pipeline().writeAndFlush(raw(3));
            worker.next(channel);
            ByteBuf first = channel.readOutbound(), direct = channel.readOutbound();
            assertArrayEquals(new byte[]{1}, decoder.decode(extract(first), null).get(0));
            assertEquals(2, direct.readUnsignedByte()); first.release(); direct.release();
            channel.pipeline().writeAndFlush(raw(7)); worker.next(channel);
            ByteBuf restarted = channel.readOutbound(), barrier = channel.readOutbound();
            byte[] frame = extract(restarted);
            try (var fresh = new EgressFrameCodec(3)) { assertArrayEquals(new byte[]{3}, fresh.decode(frame, null).get(0)); }
            assertArrayEquals(new byte[]{3}, decoder.decode(frame, null).get(0)); restarted.release(); barrier.release();
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
    @Test void receiveDirectPacketsAndBundleBoundariesWaitForEarlierDecode() {
        Worker worker = new Worker();
        var handler = new EgressInboundHandler(new Connection(PacketFlow.CLIENTBOUND), EgressTransportTest::extract, worker, (ctx, cause) -> ctx.close());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try (var encoder = new EgressFrameCodec(3)) {
            byte[] frame = encoder.encode(List.of(new EgressFrameCodec.PacketBytes("fixture:state", new byte[]{1})), 1, null).bytes();
            channel.writeInbound(wrap(frame)); channel.writeInbound(raw(7)); channel.writeInbound(raw(2));
            assertNull(channel.readInbound()); worker.next(channel);
            for (int expected : new int[]{1,7,2}) { ByteBuf bytes = channel.readInbound(); assertEquals(expected, bytes.readUnsignedByte()); bytes.release(); }
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
    @Test void corruptInboundClosesAndNeverEmitsAnyPartOfTheFrameOrFollowingPacket() {
        Worker worker = new Worker();
        EmbeddedChannel channel = new EmbeddedChannel(new EgressInboundHandler(new Connection(PacketFlow.CLIENTBOUND), EgressTransportTest::extract, worker, (ctx, cause) -> ctx.close()));
        try (var encoder = new EgressFrameCodec(3)) {
            byte[] frame = encoder.encode(List.of(new EgressFrameCodec.PacketBytes("fixture:event", new byte[]{1})), 1, null).bytes();
            frame[frame.length - 1] ^= 1;
            ByteBuf direct = raw(2); channel.writeInbound(wrap(frame)); channel.writeInbound(direct); worker.next(channel);
            assertFalse(channel.isActive()); assertNull(channel.readInbound()); assertEquals(0, direct.refCnt());
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
    @Test void hardQueueLimitFailsClearlyAndReleasesAllBufferedBytes() {
        Worker worker = new Worker(); AtomicBoolean enabled = new AtomicBoolean(true);
        EmbeddedChannel channel = new EmbeddedChannel(output(worker, enabled));
        try {
            ByteBuf bytes = Unpooled.buffer(EgressOutboundHandler.HARD_LIMIT + 1).writeZero(EgressOutboundHandler.HARD_LIMIT + 1);
            ChannelPromise promise = channel.newPromise(); channel.pipeline().write(bytes, promise);
            assertTrue(promise.isDone()); assertFalse(promise.isSuccess()); assertFalse(channel.isActive()); assertEquals(0, bytes.refCnt());
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
    @Test void softLimitDrainsExistingAggregatesThenBypassesWithoutDroppingOrReordering() {
        Worker worker = new Worker(); AtomicBoolean enabled = new AtomicBoolean(true);
        EmbeddedChannel channel = new EmbeddedChannel(output(worker, enabled));
        try (var decoder = new EgressFrameCodec(3)) {
            for (int i = 0; i < 70; i++) {
                ByteBuf bytes = Unpooled.buffer(100 * 1024).writeZero(100 * 1024);
                bytes.setByte(0, 1); bytes.setInt(bytes.writerIndex() - 4, i); channel.pipeline().writeAndFlush(bytes);
            }
            while (!worker.jobs.isEmpty()) worker.next(channel);
            int received = 0, direct = 0;
            ByteBuf bytes;
            while ((bytes = channel.readOutbound()) != null) {
                byte[] frame = extract(bytes);
                if (frame == null) { assertEquals(received++, bytes.getInt(bytes.writerIndex() - 4)); direct++; }
                else for (byte[] packet : decoder.decode(frame, null)) {
                    int n = packet.length;
                    int sequence = (packet[n-4]&255)<<24 | (packet[n-3]&255)<<16 | (packet[n-2]&255)<<8 | packet[n-1]&255;
                    assertEquals(received++, sequence);
                }
                bytes.release();
            }
            assertEquals(70, received); assertTrue(direct > 0); assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }

    @Test void protocolReplacementKeepsOldBindingAndStartsIndependentEpochAfterBarrier() {
        Worker worker = new Worker();
        var nextBinding = new EgressProtocol() {
            public ConnectionProtocol id() { return ConnectionProtocol.PLAY; }
            public PacketFlow flow() { return PacketFlow.CLIENTBOUND; }
            public void encode(ByteBuf output, Packet<?> packet) { throw new UnsupportedOperationException(); }
            public BundlerInfo bundlerInfo() { return null; }
        };
        var binding = new java.util.concurrent.atomic.AtomicReference<EgressProtocol>(PLAY);
        List<EgressProtocol> usedBindings = new ArrayList<>();
        var handler = new EgressOutboundHandler(new Connection(PacketFlow.SERVERBOUND), () -> true,
                bytes -> new EgressPipeline.Metadata("fixture:state", binding.get(), bytes.getUnsignedByte(0) == 7),
                (protocol, bytes, frame) -> { usedBindings.add(protocol); bytes.writeByte(99).writeBytes(frame); }, worker, (ctx, cause) -> ctx.close());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try (var receiver = new EgressFrameCodec(3)) {
            channel.pipeline().writeAndFlush(raw(1)); channel.pipeline().writeAndFlush(raw(7));
            binding.set(nextBinding); channel.pipeline().writeAndFlush(raw(2)); channel.pipeline().writeAndFlush(raw(7));
            worker.next(channel); worker.next(channel);
            ByteBuf old = channel.readOutbound(), boundary = channel.readOutbound(), fresh = channel.readOutbound(), last = channel.readOutbound();
            assertArrayEquals(new byte[]{1}, receiver.decode(extract(old), null).get(0));
            assertArrayEquals(new byte[]{2}, receiver.decode(extract(fresh), null).get(0));
            try (var newReceiver = new EgressFrameCodec(3)) { assertArrayEquals(new byte[]{2}, newReceiver.decode(extract(fresh), null).get(0)); }
            assertSame(PLAY, usedBindings.get(0)); assertSame(nextBinding, usedBindings.get(1));
            old.release(); boundary.release(); fresh.release(); last.release();
        } finally { channel.finishAndReleaseAll(); worker.cleanup(); }
    }
}
