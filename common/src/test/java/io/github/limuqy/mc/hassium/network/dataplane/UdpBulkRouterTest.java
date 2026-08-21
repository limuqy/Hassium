package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4 — {@link UdpBulkRouter} 单测。
 *
 * <p>覆盖 plan §463-487：health penalty 移除高 SRTT 会话、exclusive 模式连续 DROPPED 后降级 PRIMARY、
 * share 模式按 weight 在 PRIMARY/DATA 间轮询、enqueue 成功后 drop 计数清零、isLeaseActive=false 不参与选路。
 * 全部用 {@link FakeTarget} 注入，零网络/KCP 依赖。
 */
class UdpBulkRouterTest {

    private static final int TYPE = DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK;
    private static final byte[] PAY = new byte[] {1};

    private FakeTarget fast() { return new FakeTarget(1, 1, 50); }
    private FakeTarget slow() { return new FakeTarget(2, 1, 5_000); }

    @Test
    @DisplayName("health penalty: SRTT > hardRttMs 的会话不被选中，fast 永远胜出")
    void healthPenaltyRemovesHighRttSessionFromSelection() {
        FakeTarget fast = fast();
        FakeTarget slow = slow();
        UdpBulkRouter router = new UdpBulkRouter(1_000);
        UdpBulkRouter.PlayerSessions ps = UdpBulkRouter.PlayerSessions.of(List.of(fast, slow));

        for (int i = 0; i < 16; i++) {
            BulkRouteTarget chosen = router.select(ps, "exclusive", 100, 3);
            assertSame(fast, chosen, "iter " + i);
        }
        assertEquals(0, fast.enqueueCount);
        // select 不入队；用 route 才入队
    }

    @Test
    @DisplayName("exclusive 3 次无候选 DROPPED 后第 4 次返回 PRIMARY 并置 degraded")
    void exclusiveDegradesAfterThreeNoCandidateDrops() {
        UdpBulkRouter router = new UdpBulkRouter(1_000);
        // 已关闭的候选 → noneHealthy
        FakeTarget dead = new FakeTarget(1, 1, 10) { @Override public boolean isHealthy() { return false; } };
        UdpBulkRouter.PlayerSessions ps = UdpBulkRouter.PlayerSessions.of(List.of(dead));

        assertEquals(UdpBulkRouter.RouteDecision.DROPPED, router.route(ps, "exclusive", 100, 3, TYPE, PAY));
        assertFalse(ps.degraded());
        assertEquals(1, ps.consecutiveDrops());

        assertEquals(UdpBulkRouter.RouteDecision.DROPPED, router.route(ps, "exclusive", 100, 3, TYPE, PAY));
        assertEquals(2, ps.consecutiveDrops());
        assertFalse(ps.degraded());

        assertEquals(UdpBulkRouter.RouteDecision.DROPPED, router.route(ps, "exclusive", 100, 3, TYPE, PAY));
        assertEquals(3, ps.consecutiveDrops());

        // 第 4 次：degradeAfterDrops 触发 → PRIMARY，且 degraded=true
        assertEquals(UdpBulkRouter.RouteDecision.PRIMARY, router.route(ps, "exclusive", 100, 3, TYPE, PAY));
        assertTrue(ps.degraded(), "third drop then degrade -> PRIMARY on the 4th call");
    }

    @Test
    @DisplayName("share 模式：PRIMARY 与唯一 DATA 候选按权重 WRR；DATA 路径成功 enqueue + drop 计数清零")
    void shareModeWrrPrimaryAndDataAndDropResetOnEnqueueSuccess() {
        FakeTarget data = fast();
        UdpBulkRouter router = new UdpBulkRouter(1_000);
        // primaryWeight=1, data weight=1 → 交替。规定 share：route 返回 DATA 时入队；返回 PRIMARY 时 caller 走 TCP。
        UdpBulkRouter.PlayerSessions ps = UdpBulkRouter.PlayerSessions.of(List.of(data));

        // multi-round 抽样：100 次中出现两种决策。
        int dataHits = 0, primaryHits = 0;
        for (int i = 0; i < 100; i++) {
            UdpBulkRouter.RouteDecision d = router.route(ps, "share", 1, 3, TYPE, PAY);
            if (d == UdpBulkRouter.RouteDecision.DATA_SENT) dataHits++;
            else if (d == UdpBulkRouter.RouteDecision.PRIMARY) primaryHits++;
            else fail("unexpected " + d);
        }
        assertEquals(50, dataHits, "weight equal => half to each");
        assertEquals(50, primaryHits);
        assertEquals(50, data.enqueueCount);   // 每次 DATA_SENT 入队一次；share 50/50 => 50 次
        assertEquals(0, ps.consecutiveDrops());
        assertFalse(ps.degraded());
    }

    @Test
    @DisplayName("enqueue 失败累计 drop；3 次后第 4 次降级 PRIMARY")
    void enqueueFailureArcsIntoPrimaryDegrade() {
        FakeTarget data = new FakeTarget(1, 1, 50) { @Override public boolean enqueueAuthenticated(int t, byte[] p) { return false; } };
        UdpBulkRouter router = new UdpBulkRouter(1_000);
        UdpBulkRouter.PlayerSessions ps = UdpBulkRouter.PlayerSessions.of(List.of(data));

        // exclusive: 候选选到 data 但 enqueue 失败 → DROPPED + consecutiveDrops++
        for (int i = 0; i < 3; i++) {
            assertEquals(UdpBulkRouter.RouteDecision.DROPPED, router.route(ps, "exclusive", 100, 3, TYPE, PAY));
        }
        assertEquals(3, ps.consecutiveDrops());
        assertEquals(UdpBulkRouter.RouteDecision.PRIMARY, router.route(ps, "exclusive", 100, 3, TYPE, PAY));
        assertTrue(ps.degraded());
    }

    @Test
    @DisplayName("isLeaseActive=false 的会话在 exclusive 下被排除")
    void leaseInactiveExcludedFromSelection() {
        FakeTarget leasedOut = new FakeTarget(1, 1, 10) {
            @Override public boolean isLeaseActive(long nowMs) { return false; }
        };
        UdpBulkRouter router = new UdpBulkRouter(1_000);
        UdpBulkRouter.PlayerSessions ps = UdpBulkRouter.PlayerSessions.of(List.of(leasedOut));

        assertEquals(UdpBulkRouter.RouteDecision.DROPPED, router.route(ps, "exclusive", 100, 3, TYPE, PAY));
    }

    @Test
    @DisplayName("并发 refresh + routeAndPick（模拟 pushPool 多线程发送同一玩家 chunk）不抛 AIOOBE，决策合法")
    void concurrentRefreshAndRouteKeepsWrrStateIntact() throws Exception {
        // 回归：ServerChunkPushManager.pushPool（固定多线程）对同一玩家的多个 chunk 并发走
        // tryRouteBulk → refresh + routeAndPick。修复前 refresh 与 wrrPickShared 的 curWeights
        // check-then-act 竞态会产生 ArrayIndexOutOfBoundsException: Index N out of bounds for length 1。
        UdpBulkRouter router = new UdpBulkRouter(1_000);
        FakeTarget d1 = fast();
        FakeTarget d2 = fast();
        FakeTarget d3 = fast();
        UdpBulkRouter.PlayerSessions ps = UdpBulkRouter.PlayerSessions.of(List.of(d1, d2, d3));

        // 快照在 0~3 个会话间抖动，最大化 curWeights 重分配竞争（与生产 bind/lease 抖动同构）。
        @SuppressWarnings("unchecked")
        List<FakeTarget>[] snapshots = new List[] {
                List.of(), List.of(d1), List.of(d1, d2), List.of(d1, d2, d3)
        };

        int threads = 8;
        int roundsPerThread = 2_000;
        AtomicInteger dataSent = new AtomicInteger();
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger dropped = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    for (int i = 0; i < roundsPerThread; i++) {
                        // 与生产一致：worksetsFor 先 refresh 快照，再 routeAndPick；线程间任意交错。
                        ps.refresh(snapshots[(threadIdx + i) % snapshots.length]);
                        UdpBulkRouter.RouteOutcome o =
                                router.routeAndPick(ps, "share", 1, 3, TYPE, PAY);
                        if (o.decision() == UdpBulkRouter.RouteDecision.DATA_SENT) {
                            assertNotNull(o.chosenOrNull(), "DATA_SENT 必须携带选中 target");
                            dataSent.incrementAndGet();
                        } else if (o.decision() == UdpBulkRouter.RouteDecision.PRIMARY) {
                            primary.incrementAndGet();
                        } else {
                            dropped.incrementAndGet();
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS); // 任何 AIOOBE 都会在此以 ExecutionException 上抛
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, dropped.get(), "share 模式无候选应退 PRIMARY，不该出现 DROPPED");
        assertTrue(dataSent.get() > 0, "抖动含健康候选，应出现 DATA_SENT");
        assertTrue(primary.get() > 0, "抖动含空快照，应出现 PRIMARY");
        // enqueue 在 ps monitor 内原子执行，但 FakeTarget 计数 ++ 非原子（测试侧），只允许偏小。
        assertTrue(d1.enqueueCount + d2.enqueueCount + d3.enqueueCount <= dataSent.get());
    }

    /** 轻量 fake。 */
    static class FakeTarget implements BulkRouteTarget {
        final int eid; final int w; final long srtt;
        int enqueueCount = 0;
        FakeTarget(int eid, int w, long srtt) { this.eid = eid; this.w = w; this.srtt = srtt; }
        @Override public int endpointId() { return eid; }
        @Override public int weight() { return w; }
        @Override public boolean isHealthy() { return srtt <= 1_000; }
        @Override public boolean isWritable() { return true; }
        @Override public boolean isClosed() { return false; }
        @Override public boolean isLeaseActive(long nowMs) { return true; }
        @Override public ReliableDatagramSession.Metrics metrics() {
            return new ReliableDatagramSession.Metrics(srtt, 0, 0, true);
        }
        @Override public boolean enqueueAuthenticated(int type, byte[] payload) { enqueueCount++; return true; }
    }
}
