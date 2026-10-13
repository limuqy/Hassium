package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.compat.PacketId;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AggregationOrderingTest {
    @Test
    void pendingBundleCannotPassItsBufferedPlayerInformation() throws Exception {
        List<String> sent = new ArrayList<>();
        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            public boolean isConnected() { return true; }
            public void send(Packet<?> packet) { sent.add("bundle"); }
        };
        var bundle = new ClientboundBundlePacket(List.of());
        HassiumAggregationManager.setSender((conn, buf) -> {
            try { sent.add("info"); } finally { buf.release(); }
        });
        try {
            HassiumConnectionRegistry.markPending(connection);
            assertTrue(HassiumAggregationManager.takeOverBundle(bundle, connection));
            Field buffers = HassiumAggregationManager.class.getDeclaredField("PACKET_BUFFER");
            buffers.setAccessible(true);
            Object buffer = ((Map<?, ?>) buffers.get(null)).get(connection);
            Field packets = buffer.getClass().getDeclaredField("packets");
            packets.setAccessible(true);
            @SuppressWarnings("unchecked") List<Object> entries = (List<Object>) packets.get(buffer);
            entries.add(0, new AggregatedSubPacket(PacketId.parse("audit:info"), new byte[1]));
            HassiumAggregationManager.flushConnectionSync(connection);
            assertTrue(sent.isEmpty());
            HassiumConnectionRegistry.markEnabled(connection);
            HassiumAggregationManager.flushConnectionSync(connection);
            assertEquals(List.of("info", "bundle"), sent);
        } finally {
            HassiumAggregationManager.discardConnection(connection);
            HassiumConnectionRegistry.markDisabled(connection);
            HassiumAggregationManager.setSender(null);
        }
    }

    @Test
    void slowDecodeCannotLetTheFollowingBundleMainThreadHopPass() throws Exception {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        List<String> applied = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        try {
            AggregationDecodeQueue.enqueueOrdered(connection, (Runnable) () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                applied.add("info");
            }, true);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(AggregationDecodeQueue.enqueueOrdered(connection, (Runnable) () -> {
                applied.add("bundle");
                done.countDown();
            }, false));
            assertTrue(applied.isEmpty());
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("info", "bundle"), applied);
        } finally {
            release.countDown();
            AggregationDecodeQueue.discard(connection);
        }
    }
}
