package io.github.limuqy.mc.hassium.cache.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMainThreadBudgetTest {

    @AfterEach
    void tearDown() {
        ClientMainThreadBudget.clearJoinBoost();
        ClientMainThreadBudget.resetCacheReadBudget();
    }

    @Test
    @DisplayName("缓存读取配额用尽后拒绝，退还后可再取")
    void cacheReadBudgetCapsThenRefunds() {
        ClientMainThreadBudget.resetCacheReadBudget();
        int cap = ClientMainThreadBudget.getHardCap();
        for (int i = 0; i < cap; i++) {
            assertTrue(ClientMainThreadBudget.tryAcquireCacheRead());
        }
        assertFalse(ClientMainThreadBudget.tryAcquireCacheRead(), "配额用尽不得继续读盘");
        ClientMainThreadBudget.refundCacheRead();
        assertTrue(ClientMainThreadBudget.tryAcquireCacheRead(), "未真正读盘应退还配额");
        assertFalse(ClientMainThreadBudget.tryAcquireCacheRead());
    }

    @Test
    @DisplayName("JoinBoost / ready 预留：dispatcher 只拿一半预算给 drainReady")
    void joinBoostReservesHalfBudgetForDrainReady() {
        long budgetNs = 30_000_000L;
        assertEquals(budgetNs, ClientMainThreadBudget.dispatcherShareNs(budgetNs, false),
                "无需预留 drainReady：dispatcher 可用满额");
        assertEquals(budgetNs / 2L, ClientMainThreadBudget.dispatcherShareNs(budgetNs, true),
                "JoinBoost 或 ready 非空：dispatcher 与 drainReady 对半分账");
        assertEquals(0L, ClientMainThreadBudget.dispatcherShareNs(0L, true));
    }

    @Test
    @DisplayName("初始 10s 窗口过期后，封顶内的 apply 仍续期 JoinBoost")
    void noteChunkApplyActivityRenewsAfterInitialWindowElapses() {
        ClientMainThreadBudget.startJoinBoost();
        if (!ClientMainThreadBudget.isJoinBoostActive()) {
            return; // joinBoostEnabled=false 的测试环境跳过
        }
        ClientMainThreadBudget.elapseInitialJoinBoostWindowForTest();
        assertFalse(ClientMainThreadBudget.isJoinBoostActive(), "初始窗口应已过期");
        ClientMainThreadBudget.noteChunkApplyActivity();
        assertTrue(ClientMainThreadBudget.isJoinBoostActive(),
                "30s 封顶内的权威 apply 必须把 JoinBoost 续上，否则 ROUND1 ~10s 掉预算");
    }
}
