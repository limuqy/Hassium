package io.github.limuqy.mc.hassium.network.gateway;

import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 预热会话 TTL 清扫（T4 B3）：resume 会话登记后无续流完成 → 到期移除（走 finishRemoval
 * 完整清理链：ServerChunkPushManager.removePlayer + removal hook + detachPlayer）；
 * 正常续流（C2S sink 挂载）/ 新会话覆盖 / 非 resume 会话不受影响（风险 8 裁决）。
 *
 * <p>测试直接构造会话 + register（包私有构造），绕开握手线格式——与 T1 的
 * HandshakeStateTail 改动并行时零碰撞。到期模拟：sweepExpired 接受 nowMs 注入。
 */
class GatewayPlayerRegistryTtlTest {

    private GatewayServer server;
    private GatewayPlayerRegistry registry;

    @BeforeEach
    void setUp() {
        server = GatewayServer.getInstance();
        server.setInfoProvider(null);   // 默认：接受握手、压缩/UDP/SeedGen 关闭
        server.setLoginSink(null);
        registry = server.registry();
    }

    @AfterEach
    void tearDown() {
        server.stop(); // 幂等
        registry.clear(); // 逐会话走完整清理链
    }

    /** 登记一个 resume 会话（无 C2S sink = 无续流完成）；通道由 EmbeddedChannel 提供。 */
    private GatewayPlayerSession registerResumeSession(UUID playerId) {
        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        GatewayPlayerSession session = new GatewayPlayerSession(
                playerId, channel, true, 1L, null);
        registry.register(session);
        return session;
    }

    /** TTL 到期（nowMs 注入为当前时刻 + ttl + 1，保证 age > ttl）。 */
    private static long expiredNow(long ttlMs) {
        return System.currentTimeMillis() + ttlMs + 1;
    }

    /** 无续流完成 + TTL 到期 → 移除（registry 空 + removal hook 清理链触发）。 */
    @Test
    void ttlExpiredSweepsUnresumedResumeSession() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<UUID> removedByHook = new AtomicReference<>();
        registry.addPlayerRemovalHook(removedByHook::set);
        GatewayPlayerSession session = registerResumeSession(playerId);
        assertNotNull(registry.get(playerId));

        long ttlMs = 60_000L;
        int swept = registry.sweepExpired(expiredNow(ttlMs), ttlMs);

        assertEquals(1, swept, "无续流完成的 resume 会话到期应移除");
        assertNull(registry.get(playerId), "registry 应清空该会话");
        assertEquals(playerId, removedByHook.get(), "清理走既有 removal hook 链");
        assertNull(session.channel().playerSession(), "finishRemoval 已 detachPlayer 解绑");
    }

    /** TTL 未到期 → 保留（续流窗口内不误伤）。 */
    @Test
    void ttlNotExpiredKeepsSession() {
        UUID playerId = UUID.randomUUID();
        registerResumeSession(playerId);

        long ttlMs = 60_000L;
        int swept = registry.sweepExpired(System.currentTimeMillis(), ttlMs);

        assertEquals(0, swept, "未到期不清扫");
        assertNotNull(registry.get(playerId));
    }

    /** 续流完成（C2S sink 挂载 = 物化成功）→ 即使超时也保留（正常续流不受影响）。 */
    @Test
    void resumedSessionWithSinkNotSwept() {
        UUID playerId = UUID.randomUUID();
        GatewayPlayerSession session = registerResumeSession(playerId);
        session.setC2SSink((pid, payload) -> { }); // 续流完成信号

        long ttlMs = 60_000L;
        int swept = registry.sweepExpired(expiredNow(ttlMs), ttlMs);

        assertEquals(0, swept, "续流完成者不参与 TTL（归断连清理路径）");
        assertSame(session, registry.get(playerId));
    }

    /**
     * 风险 8：续流成功后新会话覆盖旧会话 → 旧会话停止计时。
     * 旧会话已不在表内（register put 覆盖），清扫只针对当前登记会话——即使旧会话
     * 的 age 远超 TTL 也不产生清扫；新会话续流完成 → 全部保留。
     */
    @Test
    void resumeOverwriteStopsOldSessionClock() {
        UUID playerId = UUID.randomUUID();
        registerResumeSession(playerId);
        assertNotNull(registry.get(playerId));

        // 客户端重连续流：新通道 + 新会话覆盖旧会话（register 覆盖，不清理旧会话）
        GatewayPlayerSession newSession = registerResumeSession(playerId);
        assertSame(newSession, registry.get(playerId), "新会话覆盖旧会话");

        // 新会话续流完成（sink 挂载）；清扫注入远超 TTL 的时刻
        newSession.setC2SSink((pid, payload) -> { });
        long ttlMs = 60_000L;
        int swept = registry.sweepExpired(expiredNow(ttlMs), ttlMs);

        assertEquals(0, swept, "覆盖后旧会话不在表内（不计时），新会话续流完成不受影响");
        assertSame(newSession, registry.get(playerId));
    }

    /** 新会话覆盖后若也未完成续流 → 新会话按自身登记时间起算 TTL，到期同样清理。 */
    @Test
    void overwrittenUnresumedNewSessionExpires() {
        UUID playerId = UUID.randomUUID();
        registerResumeSession(playerId); // 旧会话（覆盖掉）

        GatewayPlayerSession newSession = registerResumeSession(playerId); // 新会话，无 sink
        assertSame(newSession, registry.get(playerId));

        long ttlMs = 60_000L;
        int swept = registry.sweepExpired(expiredNow(ttlMs), ttlMs);

        assertEquals(1, swept, "新会话自身无续流完成且到期 → 清理");
        assertNull(registry.get(playerId));
    }

    /** 非 resume 会话（登录桥/标准流程）不参与 TTL（各自路径：pending attach 10s / 断连清理）。 */
    @Test
    void nonResumeSessionNotSwept() {
        UUID playerId = UUID.randomUUID();
        GatewayChannel channel = GatewayChannel.openEmbedded(server);
        GatewayPlayerSession session = new GatewayPlayerSession(
                playerId, channel, false, Long.MIN_VALUE, null);
        registry.register(session);

        long ttlMs = 60_000L;
        int swept = registry.sweepExpired(expiredNow(ttlMs), ttlMs);

        assertEquals(0, swept, "非 resume 会话不参与预热 TTL");
        assertSame(session, registry.get(playerId));
    }

    /** ttlMs <= 0（配置禁用防御）→ 不清扫。 */
    @Test
    void nonPositiveTtlDisablesSweep() {
        UUID playerId = UUID.randomUUID();
        registerResumeSession(playerId);

        assertEquals(0, registry.sweepExpired(expiredNow(0), 0));
        assertEquals(0, registry.sweepExpired(expiredNow(-1), -1));
        assertNotNull(registry.get(playerId));
    }
}
