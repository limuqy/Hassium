package io.github.limuqy.mc.hassium.network.core.migration;

import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.ResumeTicket;
import io.github.limuqy.mc.hassium.network.ResumeTicketValidator;
import io.github.limuqy.mc.hassium.network.ServerLoadReporter;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameType;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.OutboundConnection;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 迁移引擎单测：票据构造+验签往返 / 策略阈值判定 / 心跳超时故障触发 /
 * 负载报告触发路由 / 预热生命周期。
 */
class MigrationEngineTest {

    @BeforeAll
    static void setupResumeKey() {
        ResumeTicket.setSharedKey("hassium-test-key".getBytes(StandardCharsets.UTF_8));
    }

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AtomicLong clock = new AtomicLong(100_000L);

    private MigrationEngine engine() {
        MigrationEngine engine = new MigrationEngine();
        engine.setClock(clock::get);
        engine.setPlayerIdSource(() -> PLAYER);
        engine.setPlayerStateSource(() -> new PlayerStateReport(10, 20, 30, 90, 0, "minecraft:overworld"));
        return engine;
    }

    private static MigrationEngine.Sink recordingSink(AtomicLong faults, AtomicReference<String> trigger) {
        return new MigrationEngine.Sink() {
            @Override
            public void onFault() {
                if (faults != null) {
                    faults.incrementAndGet();
                }
            }

            @Override
            public void onPolicyTrigger(String reason) {
                if (trigger != null) {
                    trigger.set(reason);
                }
            }
        };
    }

    private static ServerLoadReporter.ServerLoadReport report(double load, double tps) {
        return new ServerLoadReporter.ServerLoadReport(0L, 0, load, tps, 100L, 1024L, 5);
    }

    // ==================== 续流票据：构造 + 验签往返 ====================

    @Test
    void resumeTicketConstructionVerifyRoundtrip() {
        byte[] keyBefore = ResumeTicket.sharedKey();
        try {
            MigrationEngine engine = engine();
            ResumeTicket t1 = engine.createResumeTicket();
            assertEquals(PLAYER, t1.playerId());
            assertEquals(1L, t1.epoch(), "首票 epoch=1（进程生命周期单调递增）");
            assertTrue(t1.verify(), "共享密钥验签通过");
            assertTrue(ResumeTicketValidator.verifyRequest(PLAYER, t1.encode()).accepted(),
                    "服务端（B）验票通过");

            ResumeTicket t2 = engine.createResumeTicket();
            assertEquals(2L, t2.epoch(), "epoch 递增");
            assertTrue(ResumeTicketValidator.verifyRequest(PLAYER, t2.encode()).accepted());

            // 重放 / 旧 epoch → 拒绝
            assertFalse(ResumeTicketValidator.verifyRequest(PLAYER, t1.encode()).accepted(),
                    "旧票重放拒绝");
            // 跨玩家拒绝
            assertFalse(ResumeTicketValidator.verifyRequest(UUID.randomUUID(), t2.encode()).accepted());
            // 错误密钥拒绝
            ResumeTicket.setSharedKey(new byte[32]);
            assertFalse(t1.verify(), "换密钥后验签失败");
        } finally {
            ResumeTicket.setSharedKey(keyBefore);
        }
    }

    // ==================== 策略触发：负载阈值 ====================

    @Test
    void policyThresholds() {
        MigrationEngine engine = engine();
        engine.setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 10000L, 60000L, 10000L, 0.5, true));

        assertFalse(engine.evaluatePolicy(report(2.0, 19.5)).migrate(), "健康负载不触发");
        assertTrue(engine.evaluatePolicy(report(2.0, 12.0)).migrate(), "TPS 低于阈值触发");
        assertTrue(engine.evaluatePolicy(report(9.5, 19.5)).migrate(), "负载均值超阈值触发");
        assertFalse(engine.evaluatePolicy(report(-1.0, 19.5)).migrate(), "平台不支持负载（-1）无信号");
        assertFalse(engine.evaluatePolicy(null).migrate(), "无报告不触发");
    }

    @Test
    void maintenanceWindow() {
        MigrationEngine engine = engine();
        // 时钟固定到 01:30（本地时区）
        long now = java.time.LocalDateTime.of(2026, 8, 9, 1, 30)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        clock.set(now);
        engine.setPolicy(new MigrationPolicy(15.0, 4.0, "01:00-02:00", 5000L, 10000L, 60000L, 10000L, 0.5, true));
        assertTrue(engine.evaluatePolicy(report(2.0, 19.5)).migrate(), "窗口内健康负载也触发（维护）");
        engine.setPolicy(new MigrationPolicy(15.0, 4.0, "03:00-04:00", 5000L, 10000L, 60000L, 10000L, 0.5, true));
        assertFalse(engine.evaluatePolicy(report(2.0, 19.5)).migrate(), "窗口外不触发");
        // 跨午夜窗口
        engine.setPolicy(new MigrationPolicy(15.0, 4.0, "23:00-02:00", 5000L, 10000L, 60000L, 10000L, 0.5, true));
        assertTrue(engine.evaluatePolicy(report(2.0, 19.5)).migrate(), "跨午夜窗口覆盖 01:30");
    }

    @Test
    void loadReportRoutesToSink() {
        MigrationEngine engine = engine();
        AtomicReference<String> trigger = new AtomicReference<>();
        engine.setSink(recordingSink(null, trigger));
        engine.setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 10000L, 60000L, 10000L, 0.5, true));

        engine.onLoadReport(report(2.0, 19.5));
        assertNull(trigger.get(), "健康负载不触发 sink");
        engine.onLoadReport(report(2.0, 10.0));
        assertNotNull(trigger.get(), "低 TPS 触发 sink");
        assertTrue(trigger.get().contains("tps"));
        assertEquals(1, engine.loadTriggers());
    }

    // ==================== 故障触发：心跳超时模拟 ====================

    @Test
    void faultTriggerOnHeartbeatSilence() {
        MigrationEngine engine = engine();
        AtomicLong faults = new AtomicLong();
        engine.setSink(recordingSink(faults, null));
        // N2：silentTimeoutMs 默认 10000（失效识别 ≤15s；显式配置优先于 faultTimeoutMs）
        engine.setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 10000L, 60000L, 10000L, 0.5, true));

        long t0 = clock.get();
        OutboundConnection conn = OutboundConnection.openEmbedded(
                HandshakeCodec.ClientRequestOptions.defaults(),
                new OutboundConnection.Listener() {
                    @Override
                    public void onOpen(OutboundConnection c) {
                    }

                    @Override
                    public void onHandshakeAccepted(HandshakeCodec.ServerResponse r) {
                    }

                    @Override
                    public void onHandshakeRejected(String reason) {
                    }

                    @Override
                    public void onError(Throwable cause) {
                    }
                });
        engine.bindHeartbeatTarget(conn);
        EmbeddedChannel embedded = (EmbeddedChannel) conn.channel();
        embedded.readOutbound(); // 丢弃握手帧

        // 心跳周期到点 → 发 HEARTBEAT
        clock.set(t0 + 5000);
        engine.tick(clock.get());
        ByteBuf frameBuf = embedded.readOutbound();
        assertNotNull(frameBuf, "应发出 HEARTBEAT 帧");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(frameBuf);
            assertNotNull(frame);
            assertEquals(ControlFrameType.HEARTBEAT, frame.type());
            frame.payload().release();
        } finally {
            frameBuf.release();
        }

        // 入站活动（主控回显/推送）重置静默计时
        clock.set(t0 + 6000);
        engine.noteInboundActivity();

        // 静默超过生效静默超时（10000，默认 → 失效识别 ≤15s）→ 故障触发一次
        clock.set(t0 + 6000 + 10000 + 1);
        engine.tick(clock.get());
        assertEquals(1, faults.get(), "心跳超时触发故障");
        engine.tick(clock.get());
        assertEquals(1, faults.get(), "故障只触发一次（去重）");
        assertEquals(1, engine.faultsDetected());
        conn.close();
    }

    // ==================== B2/N2：新参数生效 ====================

    @Test
    void heartbeatIntervalFromPolicy() {
        MigrationEngine engine = engine();
        AtomicLong faults = new AtomicLong();
        engine.setSink(recordingSink(faults, null));
        engine.setPolicy(new MigrationPolicy(15.0, 4.0, "", 2000L, 60000L, 60000L, 10000L, 0.5, true));

        long t0 = clock.get();
        OutboundConnection conn = OutboundConnection.openEmbedded(
                HandshakeCodec.ClientRequestOptions.defaults(),
                new OutboundConnection.Listener() {
                    @Override
                    public void onOpen(OutboundConnection c) {
                    }

                    @Override
                    public void onHandshakeAccepted(HandshakeCodec.ServerResponse r) {
                    }

                    @Override
                    public void onHandshakeRejected(String reason) {
                    }

                    @Override
                    public void onError(Throwable cause) {
                    }
                });
        engine.bindHeartbeatTarget(conn);
        EmbeddedChannel embedded = (EmbeddedChannel) conn.channel();
        embedded.readOutbound(); // 丢弃握手帧

        // 周期 2000ms：t0+2000 未到 → 不发；t0+2000 到点 → 发
        clock.set(t0 + 1999);
        engine.tick(clock.get());
        assertNull(embedded.readOutbound(), "2000ms 周期未到不应发 HEARTBEAT");
        clock.set(t0 + 2000);
        engine.tick(clock.get());
        ByteBuf frameBuf = embedded.readOutbound();
        assertNotNull(frameBuf, "2000ms 周期到点应发 HEARTBEAT");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(frameBuf);
            assertEquals(ControlFrameType.HEARTBEAT, frame.type());
            frame.payload().release();
        } finally {
            frameBuf.release();
        }
        conn.close();
    }

    @Test
    void silentTimeoutFallbackToFaultTimeoutWhenUnset() {
        MigrationEngine engine = engine();
        AtomicLong faults = new AtomicLong();
        engine.setSink(recordingSink(faults, null));
        // silentTimeoutMs 仍为默认（未配置）→ 回退显式配置的 faultTimeoutMs=30000（既有 master.migrationFaultTimeoutMs 语义）
        engine.setPolicy(new MigrationPolicy(15.0, 4.0, "", 5000L, 10000L, 30000L, 10000L, 0.5, true));
        assertEquals(30000L, engine.policy().resolvedSilentTimeoutMs(), "未配置 silentTimeout 时回退 faultTimeout");

        long t0 = clock.get();
        OutboundConnection conn = OutboundConnection.openEmbedded(
                HandshakeCodec.ClientRequestOptions.defaults(),
                new OutboundConnection.Listener() {
                    @Override
                    public void onOpen(OutboundConnection c) {
                    }

                    @Override
                    public void onHandshakeAccepted(HandshakeCodec.ServerResponse r) {
                    }

                    @Override
                    public void onHandshakeRejected(String reason) {
                    }

                    @Override
                    public void onError(Throwable cause) {
                    }
                });
        engine.bindHeartbeatTarget(conn);
        EmbeddedChannel embedded = (EmbeddedChannel) conn.channel();
        embedded.readOutbound();

        // 10000 未触发（回退语义），30000+1 触发
        clock.set(t0 + 10000 + 1);
        engine.tick(clock.get());
        assertEquals(0, faults.get(), "回退 faultTimeout=30000 时 10s 不触发");
        clock.set(t0 + 30000 + 1);
        engine.tick(clock.get());
        assertEquals(1, faults.get(), "回退 faultTimeout=30000 后 30s 触发");
        conn.close();
    }

    // ==================== 预热生命周期 ====================

    @Test
    void prewarmLifecycleWithResumeTailOnWire() {
        MigrationEngine engine = engine();
        List<OutboundConnection> created = new ArrayList<>();
        engine.setConnectionFactory((host, port, options, tail, listener) -> {
            OutboundConnection c = OutboundConnection.openEmbedded(options, listener, tail);
            created.add(c);
            return c;
        });
        MigrationEndpoint b = new MigrationEndpoint("b.example", 25566);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        PrewarmSession session = engine.prewarm(b, new PrewarmSession.Callback() {
            @Override
            public void onReady(PrewarmSession s) {
            }

            @Override
            public void onFailed(MigrationEndpoint endpoint, Throwable cause) {
                failed.set(cause);
            }
        });
        assertEquals(1, created.size());
        assertTrue(engine.prewarmInFlight(b), "预热在飞（未就绪）");
        assertNull(engine.takePrewarm(b), "未就绪不可接管");

        // 预热握手帧：应携带续流尾（玩家位置上报 → B 侧物化 + resyncTrackedChunks）
        EmbeddedChannel embedded = (EmbeddedChannel) session.connection().channel();
        ByteBuf handshake = embedded.readOutbound();
        assertNotNull(handshake, "应已发出握手帧");
        try {
            ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(handshake);
            assertNotNull(frame);
            assertEquals(ControlFrameType.HANDSHAKE_C2S, frame.type());
            HandshakeCodec.decodeClientRequest(frame.payload());
            HandshakeStateTail.C2S tail = HandshakeStateTail.readC2S(frame.payload());
            assertNotNull(tail, "握手应携带续流状态尾");
            assertTrue(tail.resumeRequested());
            assertNotNull(tail.resumeTicket());
            assertTrue(tail.state().present(), "位置上报 present");
            assertEquals("minecraft:overworld", tail.state().dimension());
            ResumeTicket ticket = ResumeTicket.decode(tail.resumeTicket());
            assertEquals(1L, ticket.epoch());
            assertTrue(ticket.verify());
            frame.payload().release();
        } finally {
            handshake.release();
        }

        // 服务端接受续流（S2C 尾 resumeAccepted=true）→ ready + 可接管
        ByteBuf response = HandshakeCodec.encodeServerResponse(1, true, true, true,
                UdpDataPlaneHandshakeTail.S2CTail.disabled(), 0L, null, false);
        HandshakeStateTail.writeS2C(response, new HandshakeStateTail.S2C(true));
        embedded.writeInbound(ControlFrameCodec.encodeFrame(ControlFrameType.HANDSHAKE_S2C, response));
        response.release();

        assertTrue(session.ready());
        assertTrue(session.resumeAccepted());
        assertNull(failed.get());
        assertFalse(engine.prewarmInFlight(b));
        assertSame(session, engine.takePrewarm(b), "就绪后可接管");
        session.close();
    }
}
