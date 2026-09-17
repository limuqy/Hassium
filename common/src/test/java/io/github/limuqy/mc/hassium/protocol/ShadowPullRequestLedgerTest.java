package io.github.limuqy.mc.hassium.protocol;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowPullRequestLedgerTest {
    @Test
    @DisplayName("same request is idempotent while conflicting reuse is stale")
    void enforcesRequestIdentity() {
        ShadowPullRequestLedger ledger = new ShadowPullRequestLedger(2);
        UUID player = UUID.randomUUID();
        ledger.advanceEpoch(4L);
        assertEquals(ShadowPullRequestLedger.Decision.ACCEPT,
                ledger.accept(player, 4L, 9L, 100L));
        assertEquals(ShadowPullRequestLedger.Decision.DUPLICATE,
                ledger.accept(player, 4L, 9L, 100L));
        assertEquals(ShadowPullRequestLedger.Decision.STALE,
                ledger.accept(player, 4L, 9L, 101L));
        assertEquals(ShadowPullRequestLedger.Decision.STALE,
                ledger.accept(player, 3L, 10L, 100L));
    }

    @Test
    @DisplayName("epoch advance drops old requests and bounds memory")
    void advancesAndEvicts() {
        ShadowPullRequestLedger ledger = new ShadowPullRequestLedger(2);
        UUID player = UUID.randomUUID();
        assertEquals(ShadowPullRequestLedger.Decision.ACCEPT,
                ledger.accept(player, 1L, 1L, 1L));
        assertEquals(ShadowPullRequestLedger.Decision.ACCEPT,
                ledger.accept(player, 1L, 2L, 2L));
        assertEquals(ShadowPullRequestLedger.Decision.ACCEPT,
                ledger.accept(player, 1L, 3L, 3L));
        assertEquals(2, ledger.size());
        ledger.advanceEpoch(2L);
        assertEquals(0, ledger.size());
        assertEquals(ShadowPullRequestLedger.Decision.ACCEPT,
                ledger.accept(player, 2L, 1L, 1L));
    }
}
