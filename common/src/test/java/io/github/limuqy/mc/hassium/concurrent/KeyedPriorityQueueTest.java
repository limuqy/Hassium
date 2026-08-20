package io.github.limuqy.mc.hassium.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyedPriorityQueue} 通用语义测试：入队取代 / 消费侧版本校验 / 消费时重算优先级。
 */
class KeyedPriorityQueueTest {

    private static final int OP_APPLY = 0;
    private static final int OP_OTHER = 1;

    private static KeyedPriorityQueue.Key key(long pos, int op) {
        return new KeyedPriorityQueue.Key(pos, op);
    }

    private static final long POS_A = 1L;
    private static final long POS_B = 2L;

    @Test
    @DisplayName("同 key 新任务 REPLACE 旧任务：旧任务不再被消费")
    void replaceSupersedesOldEntry() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        assertEquals(KeyedPriorityQueue.OfferResult.INSERTED,
                q.offer("old", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE));
        assertEquals(KeyedPriorityQueue.OfferResult.REPLACED,
                q.offer("new", key(POS_A, OP_APPLY), 20.0, KeyedPriorityQueue.OfferPolicy.REPLACE));
        assertEquals(1, q.size());

        assertEquals("new", q.poll().item());
        assertNull(q.poll());
    }

    @Test
    @DisplayName("SKIP_IF_PRESENT 去重：同 key 新任务被丢弃")
    void skipIfPresentDropsDuplicate() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        q.offer("first", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        assertEquals(KeyedPriorityQueue.OfferResult.DUP_SKIPPED,
                q.offer("second", key(POS_A, OP_APPLY), 5.0, KeyedPriorityQueue.OfferPolicy.SKIP_IF_PRESENT));
        assertEquals(1, q.size());
        assertEquals("first", q.poll().item());
    }

    @Test
    @DisplayName("不同 op 的同位置任务互不取代")
    void differentOpDoesNotSupersede() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        q.offer("apply", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("other", key(POS_A, OP_OTHER), 20.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        assertEquals(2, q.size());

        // 按优先级出队：apply(10) 先于 other(20)
        assertEquals("apply", q.poll().item());
        assertEquals("other", q.poll().item());
    }

    @Test
    @DisplayName("不同位置的同 op 任务互不取代")
    void differentPosDoesNotSupersede() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        q.offer("A", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("B", key(POS_B, OP_APPLY), 20.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        assertEquals(2, q.size());
        assertEquals("A", q.poll().item());
        assertEquals("B", q.poll().item());
    }

    @Test
    @DisplayName("null key 任务无取代语义且恒为 current")
    void nullKeyBehavesLikePlainQueue() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        q.offer("g1", null, 5.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("g2", null, 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        assertEquals(2, q.size());
        assertEquals("g2", q.poll().item());
        assertEquals("g1", q.poll().item());
    }

    @Test
    @DisplayName("消费侧版本校验：poll 后被取代的任务 isCurrent=false，执行前必须跳过")
    void polledEntryBecomesStaleAfterReplace() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        q.offer("old", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        KeyedPriorityQueue.Entry<String> polled = q.poll();
        assertTrue(q.isCurrent(polled)); // 尚未被取代：有效

        q.offer("new", key(POS_A, OP_APPLY), 20.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        assertFalse(q.isCurrent(polled)); // 已被取代：消费方必须跳过

        assertEquals("new", q.poll().item());
    }

    @Test
    @DisplayName("release 后同 key 可重新入队")
    void releaseAllowsReentry() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        q.offer("first", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        KeyedPriorityQueue.Entry<String> polled = q.poll();
        q.release(polled); // 消费完成：释放登记

        // 释放后同 key 重新入队是全新 INSERTED（而非取代旧任务）
        assertEquals(KeyedPriorityQueue.OfferResult.INSERTED,
                q.offer("second", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE));
        // 新任务在队时，第三个同 key 任务被 SKIP
        assertEquals(KeyedPriorityQueue.OfferResult.DUP_SKIPPED,
                q.offer("third", key(POS_A, OP_APPLY), 5.0, KeyedPriorityQueue.OfferPolicy.SKIP_IF_PRESENT));
        assertEquals(1, q.size());
        assertEquals("second", q.poll().item());
    }

    @Test
    @DisplayName("reoffer 保持 generation：预算放回后仍可通过版本校验")
    void reofferKeepsGeneration() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        q.offer("task", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        KeyedPriorityQueue.Entry<String> polled = q.poll();
        assertTrue(q.reoffer(polled, 30.0));
        assertTrue(q.isCurrent(polled));

        // 已被取代的 entry 不允许复活
        KeyedPriorityQueue.Entry<String> stale = q.poll();
        q.offer("newer", key(POS_A, OP_APPLY), 5.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        assertFalse(q.reoffer(stale, 1.0));
        assertEquals("newer", q.poll().item());
    }

    @Test
    @DisplayName("pollBest：队首冻结键按当前锚点刷新，不再最优则重插，近处新键先消费")
    void pollBestReprioritizes() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        // A 入队时玩家近（键 10），B 入队时玩家远（键 100）；出队时玩家已移走 A、移到 B 旁
        q.offer("A", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("B", key(POS_B, OP_APPLY), 100.0, KeyedPriorityQueue.OfferPolicy.REPLACE);

        // 刷新函数按当前位置重算：A 现在 200（远），B 现在 1（近）
        KeyedPriorityQueue.PriorityRefresher refresher = (k, old) -> k.posLong() == POS_B ? 1.0 : 200.0;
        // 队首 A(10) 刷新为 200 → 比 B(100) 差 → 重插；B 刷新为 1 → 最优，先消费
        KeyedPriorityQueue.Entry<String> first = q.pollBest(refresher, 8);
        assertEquals("B", first.item());
        assertEquals("A", q.pollBest(refresher, 8).item());
    }

    @Test
    @DisplayName("pollBest 重插次数受限：刷新键持续劣化时不饿死也不无限循环")
    void pollBestBoundedReinserts() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();
        q.offer("A", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("B", key(POS_B, OP_APPLY), 100.0, KeyedPriorityQueue.OfferPolicy.REPLACE);

        // 病态刷新：每次刷新键 +1000（键持续劣化、互为劣化源）→ 无界重插会死循环
        KeyedPriorityQueue.PriorityRefresher refresher = (k, old) -> old + 1000.0;
        KeyedPriorityQueue.Entry<String> e = q.pollBest(refresher, 2);
        assertEquals("A", e.item()); // 达到重插上限后强制返回，不饿死
        // A 重插后又被 poll 返回；队列只剩被重插的 B
        assertEquals(1, q.size());
        assertEquals("B", q.poll().item());
        assertNull(q.poll());
    }

    @Test
    @DisplayName("poll 自动丢弃过期队首，peek 顺带清理")
    void pollSkipsStaleEntries() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();

        q.offer("stale1", key(POS_A, OP_APPLY), 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("stale2", key(POS_B, OP_APPLY), 2.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("fresh", null, 3.0, KeyedPriorityQueue.OfferPolicy.REPLACE);

        // 取代 stale1 / stale2（堆中摘除）
        q.offer("s1'", key(POS_A, OP_APPLY), 4.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("s2'", key(POS_B, OP_APPLY), 5.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        // 队列实际只剩 3 个任务
        assertEquals(3, q.size());

        List<String> order = new ArrayList<>();
        KeyedPriorityQueue.Entry<String> e;
        while ((e = q.poll()) != null) {
            order.add(e.item());
        }
        assertEquals(List.of("fresh", "s1'", "s2'"), order);
    }

    @Test
    @DisplayName("removeIf 清理任务并释放 key 登记")
    void removeIfCleansRegistry() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();
        q.offer("keep", key(POS_A, OP_APPLY), 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("drop", key(POS_B, OP_APPLY), 2.0, KeyedPriorityQueue.OfferPolicy.REPLACE);

        assertTrue(q.removeIf(item -> item.equals("drop")));
        assertEquals(1, q.size());

        // key 登记已释放：同 key 重新入队应 INSERTED 而非 REPLACED
        assertEquals(KeyedPriorityQueue.OfferResult.INSERTED,
                q.offer("drop2", key(POS_B, OP_APPLY), 3.0, KeyedPriorityQueue.OfferPolicy.REPLACE));
    }

    @Test
    @DisplayName("clear 清空堆与登记")
    void clearResetsEverything() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();
        q.offer("a", key(POS_A, OP_APPLY), 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("b", key(POS_B, OP_APPLY), 2.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.clear();
        assertTrue(q.isEmpty());
        assertEquals(KeyedPriorityQueue.OfferResult.INSERTED,
                q.offer("c", key(POS_A, OP_APPLY), 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE));
    }

    @Test
    @DisplayName("同优先级任务全部被消费（堆序不保证 FIFO，仅保证不丢）")
    void equalPriorityConsumesAll() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();
        q.offer("first", null, 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("second", null, 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("third", null, 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        List<String> order = new ArrayList<>();
        KeyedPriorityQueue.Entry<String> e;
        while ((e = q.poll()) != null) {
            order.add(e.item());
        }
        assertEquals(3, order.size());
        assertEquals(java.util.Set.of("first", "second", "third"), new java.util.HashSet<>(order));
    }

    @Test
    @DisplayName("reprioritize：冻结近环在玩家移走后让位给真正近处的新任务")
    void reprioritizePromotesNewlyNearOverFrozenFar() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();
        for (int i = 0; i < 32; i++) {
            q.offer("old" + i, key(1000L + i, OP_APPLY), i * 0.1,
                    KeyedPriorityQueue.OfferPolicy.REPLACE);
        }
        q.offer("near", key(POS_B, OP_APPLY), 50.0, KeyedPriorityQueue.OfferPolicy.REPLACE);

        KeyedPriorityQueue.PriorityRefresher refresher =
                (k, old) -> k.posLong() == POS_B ? 0.0 : 100.0;
        assertEquals(33, q.reprioritize(refresher));
        assertEquals("near", q.poll().item());
    }

    @Test
    @DisplayName("满队列驱逐：更近任务挤掉最差存活任务，更远任务被拒绝")
    void evictWorstIfWorseThanPrefersNearer() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();
        q.offer("near", key(POS_A, OP_APPLY), 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("far", key(POS_B, OP_APPLY), 100.0, KeyedPriorityQueue.OfferPolicy.REPLACE);

        KeyedPriorityQueue.Entry<String> evicted = q.evictWorstIfWorseThan(10.0);
        assertEquals("far", evicted.item());
        assertEquals(1, q.size());
        assertNull(q.evictWorstIfWorseThan(1.0));
        assertEquals("near", q.poll().item());
    }

    @Test
    @DisplayName("pollBest 重插后 current 指向新键，release 能清掉登记")
    void pollBestReinsertUpdatesCurrentForRelease() {
        KeyedPriorityQueue<String> q = new KeyedPriorityQueue<>();
        q.offer("A", key(POS_A, OP_APPLY), 10.0, KeyedPriorityQueue.OfferPolicy.REPLACE);
        q.offer("B", key(POS_B, OP_APPLY), 100.0, KeyedPriorityQueue.OfferPolicy.REPLACE);

        KeyedPriorityQueue.PriorityRefresher refresher =
                (k, old) -> k.posLong() == POS_B ? 1.0 : 200.0;
        KeyedPriorityQueue.Entry<String> first = q.pollBest(refresher, 8);
        assertEquals("B", first.item());
        q.release(first);
        assertEquals(KeyedPriorityQueue.OfferResult.INSERTED,
                q.offer("B2", key(POS_B, OP_APPLY), 5.0, KeyedPriorityQueue.OfferPolicy.REPLACE));
    }
}
