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
    @DisplayName("JoinBoost：dispatcher 只拿一半预算，预留 drainReady")
    void joinBoostReservesHalfBudgetForDrainReady() {
        long budgetNs = 30_000_000L;
        assertEquals(budgetNs, ClientMainThreadBudget.dispatcherShareNs(budgetNs, false),
                "非 JoinBoost：dispatcher 可用满额");
        assertEquals(budgetNs / 2L, ClientMainThreadBudget.dispatcherShareNs(budgetNs, true),
                "JoinBoost：dispatcher 与 drainReady 对半分账");
        assertEquals(0L, ClientMainThreadBudget.dispatcherShareNs(0L, true));
    }
}
