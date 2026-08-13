package io.github.limuqy.mc.hassium.network.seedgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SeedGenQueue} 语义测试：同区块去重覆盖 / 曼哈顿距离优先 / 超时回收 / 断连清空。
 */
class SeedGenQueueTest {

    @Test
    @DisplayName("同区块去重：第二次入队返回 false 且覆盖 hash，队列大小不变")
    void enqueueDeduplicatesAndOverwrites() {
        SeedGenQueue q = new SeedGenQueue();
        ChunkPos pos = new ChunkPos(3, -2);
        assertTrue(q.enqueue(pos, 100L, new long[]{1, 2}));
        assertTrue(q.isPending(pos));
        assertEquals(1, q.size());

        // 同区块再次入队（新 SeedRef 覆盖旧 hash）
        assertFalse(q.enqueue(pos, 200L, new long[]{3, 4}));
        assertEquals(1, q.size());

        SeedGenQueue.Entry e = q.peekNearest(3, -2);
        assertNotNull(e);
        assertEquals(200L, e.contentHash());
        assertEquals(2, e.sectionHashes().length);
        assertEquals(3L, e.sectionHashes()[0]);
    }

    @Test
    @DisplayName("距离优先：取曼哈顿距离最近的未超时条目，不因入队顺序改变")
    void peekNearestPicksClosestByManhattan() {
        SeedGenQueue q = new SeedGenQueue();
        ChunkPos player = new ChunkPos(0, 0);
        // 入队顺序与距离无关
        q.enqueue(new ChunkPos(10, 10), 1L, null);
        q.enqueue(new ChunkPos(-1, 2), 2L, null);
        q.enqueue(new ChunkPos(3, 0), 3L, null);

        SeedGenQueue.Entry e = q.peekNearest(player.x, player.z);
        assertNotNull(e);
        assertEquals(new ChunkPos(-1, 2), e.pos()); // 距离 3，最近
    }

    @Test
    @DisplayName("空队列 peek 返回 null，size 为 0")
    void emptyQueueReturnsNull() {
        SeedGenQueue q = new SeedGenQueue();
        assertTrue(q.isEmpty());
        assertNull(q.peekNearest(0, 0));
        assertTrue(q.expire().isEmpty());
    }

    @Test
    @DisplayName("超时回收：超过 fallbackTimeoutMs 后 expire 返回条目并移除，peek 跳过")
    void expireReapsTimedOutEntries() {
        SeedGenQueue q = new SeedGenQueue();
        long t0 = 1_000_000L;
        SeedGenQueue.clockOverrideMs = t0;
        try {
            q.enqueue(new ChunkPos(5, 5), 1L, null);
            q.enqueue(new ChunkPos(6, 6), 2L, null);
            assertEquals(2, q.size());

            // 未超时：peek 正常返回
            assertNotNull(q.peekNearest(5, 5));

            // 超过 基数 + 深度自适应 后全部超时（时钟注入，免 30s 真实睡眠）
            SeedGenQueue.clockOverrideMs = t0 + SeedGenQueue.fallbackTimeoutMs(q.size()) + 200;

            // 超时后 peek 不再返回（全部超时）
            assertNull(q.peekNearest(5, 5));

            List<SeedGenQueue.Entry> expired = q.expire();
            assertEquals(2, expired.size());
            assertTrue(q.isEmpty());
        } finally {
            SeedGenQueue.clockOverrideMs = -1L;
        }
    }

    @Test
    @DisplayName("生成完成移除与断连清空")
    void removeAndClear() {
        SeedGenQueue q = new SeedGenQueue();
        q.enqueue(new ChunkPos(1, 1), 1L, null);
        q.enqueue(new ChunkPos(2, 2), 2L, null);

        q.remove(new ChunkPos(1, 1));
        assertFalse(q.isPending(new ChunkPos(1, 1)));
        assertTrue(q.isPending(new ChunkPos(2, 2)));
        assertEquals(1, q.size());

        q.clear();
        assertTrue(q.isEmpty());
    }
}
