package io.github.limuqy.mc.hassium.network.core.migration;

import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameType;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预热会话骨架单测：位置上报目标主控（握手尾携带玩家状态+票据）→ B 侧
 * resyncTrackedChunks 触发路径的客户端半边；失败/拒绝路径。
 */
class PrewarmSessionTest {

    private static final MigrationEndpoint MASTER_B = new MigrationEndpoint("b.example", 25566);

    private static HandshakeStateTail.C2S resumeTail() {
        PlayerStateReport state = new PlayerStateReport(12.5, 64.0, -34.0, 180.0f, 12.0f, "minecraft:overworld");
        return new HandshakeStateTail.C2S(state, true, new byte[56],
                java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
    }

    @Test
    void handshakeAcceptedMarksReady() {
        AtomicReference<PrewarmSession> readyRef = new AtomicReference<>();
        AtomicReference<Throwable> failedRef = new AtomicReference<>();
        PrewarmSession session = PrewarmSession.openEmbedded(MASTER_B, resumeTail(),
                new PrewarmSession.Callback() {
                    @Override
                    public void onReady(PrewarmSession s) {
                        readyRef.set(s);
                    }

                    @Override
                    public void onFailed(MigrationEndpoint endpoint, Throwable cause) {
                        failedRef.set(cause);
                    }
                });
        EmbeddedChannel embedded = (EmbeddedChannel) session.connection().channel();

        // 握手帧：固定字段 + 续流状态尾（位置上报 present、resumeRequested）
        ByteBuf handshake = embedded.readOutbound();
        assertNotNull(handshake);
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(handshake);
            assertEquals(ControlFrameType.HANDSHAKE_C2S, frame.type());
            HandshakeCodec.decodeClientRequest(frame.payload());
            HandshakeStateTail.C2S tail = HandshakeStateTail.readC2S(frame.payload());
            assertNotNull(tail);
            assertTrue(tail.resumeRequested());
            assertTrue(tail.state().present());
            assertEquals(12.5, tail.state().x());
            assertEquals(64.0, tail.state().y());
            assertEquals(-34.0, tail.state().z());
            assertEquals(180.0f, tail.state().yaw());
            assertEquals(12.0f, tail.state().pitch());
            assertEquals("minecraft:overworld", tail.state().dimension());
            frame.payload().release();
        } finally {
            handshake.release();
        }

        // 服务端接受 + resumeAccepted=true → onReady
        ByteBuf response = HandshakeCodec.encodeServerResponse(1, true, true, true,
                io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.S2CTail.disabled(),
                0L, null, false);
        HandshakeStateTail.writeS2C(response, new HandshakeStateTail.S2C(true));
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_S2C, response));
        response.release();

        assertTrue(session.ready());
        assertTrue(session.resumeAccepted());
        assertNotNull(readyRef.get());
        assertSameSession(session, readyRef.get());
        assertNull(failedRef.get());
        session.close();
    }

    @Test
    void handshakeRejectedFailsSession() {
        AtomicReference<PrewarmSession> readyRef = new AtomicReference<>();
        AtomicReference<Throwable> failedRef = new AtomicReference<>();
        PrewarmSession session = PrewarmSession.openEmbedded(MASTER_B, resumeTail(),
                new PrewarmSession.Callback() {
                    @Override
                    public void onReady(PrewarmSession s) {
                        readyRef.set(s);
                    }

                    @Override
                    public void onFailed(MigrationEndpoint endpoint, Throwable cause) {
                        failedRef.set(cause);
                    }
                });
        EmbeddedChannel embedded = (EmbeddedChannel) session.connection().channel();
        embedded.readOutbound(); // 丢弃握手帧

        ByteBuf response = HandshakeCodec.encodeServerResponse(1, false, false, false, null, 0, null, false);
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_S2C, response));
        response.release();

        assertFalse(session.ready());
        assertTrue(session.isTerminal());
        assertNull(readyRef.get());
        assertNotNull(failedRef.get(), "握手拒绝 → onFailed");
    }

    private static void assertSameSession(PrewarmSession expected, PrewarmSession actual) {
        if (expected != actual) {
            throw new AssertionError("expected same session");
        }
    }
}
