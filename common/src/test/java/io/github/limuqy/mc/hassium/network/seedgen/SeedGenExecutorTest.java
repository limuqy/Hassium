package io.github.limuqy.mc.hassium.network.seedgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SeedGenExecutor} 两级缓冲释放语义测试（纯逻辑静态路径）：
 * 活体 SeedRef 永远先于盲预生成、内部按当前玩家位置最近优先（消除 FIFO 头部阻塞）。
 */
class SeedGenExecutorTest {

    @Test
    @DisplayName("活体缓冲内按玩家位置最近优先释放，与入队顺序无关")
    void releasesNearestLiveFirstRegardlessOfArrivalOrder() {
        SeedGenQueue work = new SeedGenQueue();
        SeedGenQueue live = new SeedGenQueue();
        SeedGenQueue pregen = new SeedGenQueue();

        live.enqueue(new ChunkPos(-40, 0), 1L, null); // 先到但远
        live.enqueue(new ChunkPos(3, 0), 2L, null);   // 后到但近

        SeedGenExecutor.releasePendingWork(work, live, pregen, 0, 0, 1);

        SeedGenQueue.Entry e = work.peekNearest(0, 0);
        assertEquals(new ChunkPos(3, 0), e.pos());
        assertEquals(1, work.size());
        assertEquals(1, live.size());
    }

    @Test
    @DisplayName("只要还有活体 SeedRef，盲预生成即使更近也不释放")
    void liveAlwaysBeatsPregenRegardlessOfDistance() {
        SeedGenQueue work = new SeedGenQueue();
        SeedGenQueue live = new SeedGenQueue();
        SeedGenQueue pregen = new SeedGenQueue();

        live.enqueue(new ChunkPos(50, 0), 42L, null);
        pregen.enqueue(new ChunkPos(1, 0), 0L, new long[0]);

        SeedGenExecutor.releasePendingWork(work, live, pregen, 0, 0, 1);

        assertTrue(work.isPending(new ChunkPos(50, 0)));
        assertEquals(1, work.size());
        assertEquals(1, pregen.size()); // 盲预生成仍留在低优先级缓冲
    }

    @Test
    @DisplayName("活体缓冲耗尽后释放盲预生成")
    void pregenReleasedAfterLiveDrains() {
        SeedGenQueue work = new SeedGenQueue();
        SeedGenQueue live = new SeedGenQueue();
        SeedGenQueue pregen = new SeedGenQueue();

        live.enqueue(new ChunkPos(5, 0), 42L, null);
        pregen.enqueue(new ChunkPos(1, 0), 0L, new long[0]);
        pregen.enqueue(new ChunkPos(-2, 0), 0L, new long[0]);

        SeedGenExecutor.releasePendingWork(work, live, pregen, 0, 0, 96);

        // 第一轮活体优先；继续释放直到工作队列深度上限（本测试缓冲足够小，一次全释放）
        assertEquals(3, work.size());
        assertTrue(live.isEmpty());
        assertTrue(pregen.isEmpty());
        // 工作队列内按距离：活体块和最近的盲预生成块都在其中
        assertTrue(work.isPending(new ChunkPos(5, 0)));
        assertTrue(work.isPending(new ChunkPos(1, 0)));
        assertTrue(work.isPending(new ChunkPos(-2, 0)));
    }

    @Test
    @DisplayName("工作队列达到深度上限后停止释放")
    void stopsAtWorkDepthCap() {
        SeedGenQueue work = new SeedGenQueue();
        SeedGenQueue live = new SeedGenQueue();
        SeedGenQueue pregen = new SeedGenQueue();

        for (int i = 0; i < 5; i++) {
            live.enqueue(new ChunkPos(i, 0), 1L + i, null);
        }

        SeedGenExecutor.releasePendingWork(work, live, pregen, 0, 0, 2);

        assertEquals(2, work.size());
        assertEquals(3, live.size());
    }

    @Test
    @DisplayName("复现现场：几百个旧路径 SeedRef + 441 盲预生成不阻塞当前视野 SeedRef")
    void reproducesLogScenarioCurrentViewNotStarved() {
        SeedGenQueue work = new SeedGenQueue();
        SeedGenQueue live = new SeedGenQueue();
        SeedGenQueue pregen = new SeedGenQueue();

        // 模拟盲预生成 441 块：先到、hash=0（旧 FIFO 实现会先把它全部释放）
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                pregen.enqueue(new ChunkPos(-1000 + dx, -1000 + dz), 0L, new long[0]);
            }
        }
        // 模拟早期路径/初始 resync 的几百个旧 SeedRef
        for (int i = 0; i < 500; i++) {
            live.enqueue(new ChunkPos(-2000 + i % 50, -2000 + i / 50), 1L + i, null);
        }
        // 玩家当前位置刚到达的 10 个 SeedRef
        ChunkPos player = new ChunkPos(40, -20);
        for (int i = 0; i < 10; i++) {
            live.enqueue(new ChunkPos(player.x + i, player.z), 9_000L + i, null);
        }

        SeedGenExecutor.releasePendingWork(work, live, pregen, player.x, player.z, 96);

        // 当前视野 10 块必须全部进入工作队列，且工作队列没有盲预生成条目
        for (int i = 0; i < 10; i++) {
            assertTrue(work.isPending(new ChunkPos(player.x + i, player.z)),
                    "current-view chunk must be released into work queue");
        }
        assertEquals(441, pregen.size());
        assertEquals(96, work.size());
    }
}
