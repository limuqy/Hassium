package io.github.limuqy.mc.hassium.network.seedgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SeedGenExecutor} 已获服务端 SeedRef 许可的距离优先释放语义测试。
 */
class SeedGenExecutorTest {

    @Test
    @DisplayName("活体缓冲内按玩家位置最近优先释放，与入队顺序无关")
    void releasesNearestLiveFirstRegardlessOfArrivalOrder() {
        SeedGenQueue work = new SeedGenQueue();
        SeedGenQueue live = new SeedGenQueue();

        live.enqueue(new ChunkPos(-40, 0), 1L, null); // 先到但远
        live.enqueue(new ChunkPos(3, 0), 2L, null);   // 后到但近

        SeedGenExecutor.releasePendingWork(work, live, 0, 0, 1);

        SeedGenQueue.Entry e = work.peekNearest(0, 0);
        assertEquals(new ChunkPos(3, 0), e.pos());
        assertEquals(1, work.size());
        assertEquals(1, live.size());
    }


    @Test
    @DisplayName("工作队列达到深度上限后停止释放")
    void stopsAtWorkDepthCap() {
        SeedGenQueue work = new SeedGenQueue();
        SeedGenQueue live = new SeedGenQueue();

        for (int i = 0; i < 5; i++) {
            live.enqueue(new ChunkPos(i, 0), 1L + i, null);
        }

        SeedGenExecutor.releasePendingWork(work, live, 0, 0, 2);

        assertEquals(2, work.size());
        assertEquals(3, live.size());
    }

    @Test
    @DisplayName("当前视野 SeedRef 优先于远方旧条目")
    void currentViewSeedRefsAreNotStarved() {
        SeedGenQueue work = new SeedGenQueue();
        SeedGenQueue live = new SeedGenQueue();

        // 模拟早期路径/初始 resync 的几百个旧 SeedRef。
        for (int i = 0; i < 500; i++) {
            live.enqueue(new ChunkPos(-2000 + i % 50, -2000 + i / 50), 1L + i, null);
        }
        // 玩家当前位置刚到达的 10 个 SeedRef。
        ChunkPos player = new ChunkPos(40, -20);
        for (int i = 0; i < 10; i++) {
            live.enqueue(new ChunkPos(player.x + i, player.z), 9_000L + i, null);
        }

        SeedGenExecutor.releasePendingWork(work, live, player.x, player.z, 96);

        for (int i = 0; i < 10; i++) {
            assertTrue(work.isPending(new ChunkPos(player.x + i, player.z)),
                    "current-view chunk must be released into work queue");
        }
        assertEquals(96, work.size());
    }
}
